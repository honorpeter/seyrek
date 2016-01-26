package Seyrek

import Chisel._
import TidbitsDMA._
import TidbitsStreams._
import TidbitsOCM._

class NBDMInpVecCache(p: SeyrekParams, chanIDBase: Int) extends InpVecLoader(p) {
  val inOrder = false

  // TODO parametrize

  if(p.valWidth > p.mrp.dataWidth || p.mrp.dataWidth % p.valWidth != 0)
    throw new Exception("Unsupported valWidth:dataWidth ratio")

  val numReadTxns = 4
  val numCacheTxns = numReadTxns + 2
  val numLines = 1024

  if(!isPow2(numLines))
    throw new Exception("Cache lines must be power of two")

  val burstCount = 8
  val bytesPerMemWord = (p.mrp.dataWidth / 8)
  val bytesPerLine = bytesPerMemWord * burstCount
  val bytesPerElem = p.valWidth / 8
  val elemsPerLine = bytesPerLine / bytesPerElem
  val elemsPerMemWord = bytesPerMemWord / bytesPerElem
  val bitsPerLine = bytesPerLine * 8

  val numOffsBits = log2Up(elemsPerLine)
  val numIndBits = log2Up(numLines)
  val numTagBits = p.indWidth - (numOffsBits + numIndBits)

  // cache internal types
  abstract class PrintableBundle extends Bundle {
    def printfStr: String
    def printfElems: () => Seq[Node]
  }

  class CacheReq extends PrintableBundle {
    // cache internal ID
    val reqID = UInt(width = log2Up(numCacheTxns))
    // line number
    val lineNum = UInt(width = numIndBits)
    // line tag
    val tag = UInt(width = numTagBits)
    // word offset in line
    val offs = UInt(width = numOffsBits)

    val printfStr = "rid: %d lineNum: %d tag: %d offs: %d\n"
    val printfElems = {() => Seq(reqID, lineNum, tag, offs)}

    override def cloneType: this.type = new CacheReq().asInstanceOf[this.type]
  }
  class CacheTagRsp extends PrintableBundle {
    // hit or miss?
    val isHit = Bool()
    // data from original req:
    val req = new CacheReq()
    // response data if hit
    val data = UInt(width = p.valWidth)

    val printfStr = "rid: %d hit: %d data: %d \n"
    val printfElems = {() => Seq(req.reqID, isHit, data)}

    override def cloneType: this.type = new CacheTagRsp().asInstanceOf[this.type]
  }

  // cloneTypes for internal types
  val cacheReqID = UInt(width = log2Up(numCacheTxns))
  val cacheReq = new CacheReq()
  val cacheTagRsp = new CacheTagRsp()

  def printBundle(b: PrintableBundle) = {
    printf(b.printfStr, b.printfElems():_*)
  }

  // ==========================================================================

  // cloakroom -- don't carry around the entire request
  val cloakroom = Mem(p.vii, numCacheTxns)
  val cacheReqIDPool = Module(
    new ReqIDQueue(log2Up(numCacheTxns), numCacheTxns, 0)).io

  // turn the external (Aij, i, j) into a cache request
  def makeCacheReq(rq: ValIndInd, rid: UInt): CacheReq = {
    val cr = new CacheReq()
    cr.reqID := rid
    cr.tag := rq.j(p.indWidth-1, numOffsBits + numIndBits)
    cr.lineNum := rq.j(numOffsBits + numIndBits - 1, numOffsBits)
    cr.offs := rq.j(numOffsBits-1, 0)
    cr
  }

  val readyReqs = StreamJoin(inA = io.loadReq, inB = cacheReqIDPool.idOut,
    genO = cacheReq, join = makeCacheReq
  )

  // add to cloakroom when request arrives
  when( readyReqs.ready & readyReqs.valid) {
    cloakroom(readyReqs.bits.reqID) := io.loadReq.bits
  }

  // requets ready to be processed
  val reqQ = Module(new FPGAQueue(cacheReq, 4)).io

  // responses ready to be processed
  val respQCapacity = 4
  val respQ = Module(new FPGAQueue(cacheTagRsp, respQCapacity)).io

  val readyResps = Module(new StreamFork(
    genIn = cacheTagRsp, genA = cacheReqID, genB = p.wu,
    forkA = {a: CacheTagRsp => a.req.reqID},
    forkB = {a: CacheTagRsp =>
      val newWU = new WorkUnit(p.valWidth, p.indWidth)
      newWU.vectorVal := a.data
      // other WU elements will be fetched from the cloakroom
      newWU
    }
  )).io

  respQ.deq <> readyResps.in
  readyResps.outA <> cacheReqIDPool.idIn
  readyResps.outB <> io.loadRsp

  // retrieve rowInd and matrixVal upon exit
  io.loadRsp.bits.rowInd := cloakroom(readyResps.in.bits.req.reqID).i
  io.loadRsp.bits.matrixVal := cloakroom(readyResps.in.bits.req.reqID).v

  // ==========================================================================
  // structures for keeping the cache state
  // TODO initialize tag and valid bits when in init mode
  // tags and valid bits
  val tagStore = Module(new DualPortBRAM(numIndBits, 1 + numTagBits)).io
  val tagLat = 1
  val tagRead = tagStore.ports(0)
  val tagWrite = tagStore.ports(1)

  tagRead.req.writeEn := Bool(false)
  tagRead.req.writeData := UInt(0)

  // tag init logic
  val regTagInitAddr = Reg(init = UInt(0, 1+numIndBits))

  io.finished := regTagInitAddr === UInt(numLines)

  when(io.mode === SeyrekModes.START_INIT & io.start & !io.finished) {
    regTagInitAddr := regTagInitAddr + UInt(1)
    tagRead.req.writeEn := Bool(true)
  } .elsewhen(!io.start) {
    regTagInitAddr := UInt(0)
  }

  // cacheline data
  val datStore = Module(new DualPortBRAM(numIndBits, bitsPerLine)).io
  val datLat = 1
  val datRead = datStore.ports(0)
  val datWrite = datStore.ports(1)

  datRead.req.writeEn := Bool(false)

  // for tracking in-flight hits; these lines should not be written to
  val pendingLineHits = Module(new SearchableQueue(UInt(width = numIndBits), 4)).io

  // queues for storing intermediate results
  if(tagLat != datLat)
    throw new Exception("tag latency != data latency, needs fix")
  // TODO tagRespQ size should be max(tagLat, datLat) + 2
  val tagRespQ = Module(new FPGAQueue(cacheTagRsp, tagLat + 2)).io
  val missQ = Module(new FPGAQueue(cacheTagRsp, 2)).io

  // miss handler
  val missHandler = Module(new MissHandler(numReadTxns)).io
  missHandler.misses.valid := missQ.deq.valid
  missHandler.misses.bits := missQ.deq.bits.req
  missQ.deq.ready := missHandler.misses.ready
  missHandler.contextBase := io.contextBase
  missHandler.tagPort <> tagWrite
  missHandler.dataPort <> datWrite
  pendingLineHits.searchVal := missHandler.lineToCheck
  missHandler.isLineInUse := pendingLineHits.foundVal
  missHandler.mainMem <> io.mainMem

  // mix in resolved requests into the request stream
  // IMPROVE use own cache port for resolving requests?
  val reqMix = Module(new Arbiter(cacheReq, 2)).io
  missHandler.resolved <> reqMix.in(0)
  readyReqs <> reqMix.in(1)
  reqMix.out <> reqQ.enq


  // ==========================================================================
  // tag lookup logic

  // direct-mapped tag+data lookup with handshaking over latency
  // tag memory addrs have 1:1 correspondence with lines
  tagRead.req.addr := reqQ.deq.bits.lineNum
  // ...but a line spans several elements of the data memory
  val mAddrBase = reqQ.deq.bits.lineNum * UInt(burstCount)
  val mAddrOffs = reqQ.deq.bits.offs / UInt(elemsPerMemWord)
  datRead.req.addr := mAddrBase + mAddrOffs

  val tagHazard = (reqQ.deq.bits.lineNum === tagWrite.req.addr) & tagWrite.req.writeEn
  val canReadTag = (tagRespQ.count < UInt(2)) & !tagHazard

  val origReq = ShiftRegister(reqQ.deq.bits, tagLat)

  val tagMatch = tagRead.rsp.readData(numTagBits-1, 0) === origReq.tag
  val tagValid = tagRead.rsp.readData(numTagBits)
  val tagHit = tagMatch & tagValid

  reqQ.deq.ready := canReadTag

  // set up connections to the tagRespQ enq.
  tagRespQ.enq.valid := ShiftRegister(canReadTag & reqQ.deq.valid, tagLat)
  tagRespQ.enq.bits.isHit := tagHit
  tagRespQ.enq.bits.req := origReq

  // data read is slightly trickier
  // if there are multiple inp.vec elements per word, need to choose subword
  if(elemsPerMemWord > 1) {
    val subWord = origReq.offs & UInt(elemsPerMemWord - 1)
    val wordStart = subWord * UInt(p.valWidth)
    val wordEnd = ((subWord + UInt(1)) * UInt(p.valWidth)) - UInt(1)
    tagRespQ.enq.bits.data := datRead.rsp.readData(wordEnd, wordStart)
  } else {
    tagRespQ.enq.bits.data := datRead.rsp.readData
  }

  // add to pending hits when there is a hit
  val isNewHit = tagRespQ.enq.valid & tagRespQ.enq.ready & tagHit
  pendingLineHits.enq.valid := isNewHit
  pendingLineHits.enq.bits := origReq.lineNum

  // route tag lookup output into appropriate queue
  val tagRespDest = Seq(missQ.enq, respQ.enq)
  tagRespQ.deq <> DecoupledOutputDemux(tagRespQ.deq.bits.isHit, tagRespDest)

  // decrement hit-in-progress counter for this line when hit
  pendingLineHits.deq.ready := respQ.enq.valid & respQ.enq.ready

  // sanity check: dequeued pending hit line and actually read line must match
  when(respQ.enq.valid & respQ.enq.ready) {
    when(pendingLineHits.deq.bits != respQ.enq.bits.req.lineNum) {
      printf("***ERROR! mismatch between expected and queued hit line #\n ")
    }
  }

  // =========================================================================
  // debugging

  def monitorStream[T <: PrintableBundle](name: String, stream: DecoupledIO[T]) = {
    when(stream.valid & stream.ready) {
      printf("txn on " + name + ": ")
      printBundle(stream.bits)
    }
  }

  val verboseDebug = false
  if(verboseDebug) {
    monitorStream("reqQ.enq", reqQ.enq)
    monitorStream("tagRespQ.enq", tagRespQ.enq)
    monitorStream("respQ.enq", respQ.enq)
    monitorStream("miss.enq", missQ.enq)
    monitorStream("missHandler enter", missHandler.misses)
    monitorStream("missHandler resolved", missHandler.resolved)
  }

  //==========================================================================
  // miss handler -- IMPROVE spearate out as own external module?
  class MissHandler(txns: Int) extends Module {
    val io = new Bundle {
      // incoming misses
      val misses = Decoupled(cacheReq).flip
      // resolved requests
      val resolved = Decoupled(cacheReq)
      // pointer to start of x
      val contextBase = UInt(INPUT, width = p.mrp.addrWidth)
      // access to tag and data memories
      val tagPort = new OCMMasterIF(numTagBits+1, numTagBits+1, numIndBits)
      val dataPort = new OCMMasterIF(bitsPerLine, bitsPerLine, numIndBits)
      // checking against in-progress hits to lines
      val lineToCheck = UInt(OUTPUT, numIndBits)
      val isLineInUse = Bool(INPUT)
      // access to main memory for issuing load requests
      val mainMem = new GenericMemoryMasterPort(p.mrp)
    }
    // shorthands for main memory access
    val mreqQ = Module(new FPGAQueue(new GenericMemoryRequest(p.mrp), 2)).io
    val mrspQ = Module(new FPGAQueue(new GenericMemoryResponse(p.mrp), 2)).io
    mreqQ.deq <> io.mainMem.memRdReq
    io.mainMem.memRdRsp <> mrspQ.enq

    val mreq = mreqQ.enq
    val mrsp = mrspQ.deq

    // miss queues, where the misses wait for the load to complete
    /* IMPROVE replace these with a BRAM */
    val missQ = Vec.fill(txns) {
      Module(new FPGAQueue(cacheReq, 16)).io
    }
    val missQEnqSel = UInt(width = log2Up(txns))
    val missQEnq = DecoupledOutputDemux(missQEnqSel, missQ.map(x => x.enq))
    val missQDeqSel = UInt(width = log2Up(txns))
    val missQDeq = DecoupledInputMux(missQDeqSel, missQ.map(x => x.deq))
    val missQCounts = Vec(missQ.map(x => x.count))

    // registers for keeping the status of each miss load
    val loadValid = Reg(init = Bits(0, txns))
    val loadLine = Vec.fill(txns) {Reg(init = UInt(0, numIndBits))}
    val loadTag = Vec.fill(txns) {Reg(init = UInt(0, numTagBits))}
    val loadPrg = Vec.fill(txns) {Reg(init = UInt(0, 1+log2Up(burstCount)))}

    val hasFreeSlot = orR(~loadValid)
    val freePos = PriorityEncoder(~loadValid)
    val doAdd = Bool()
    val doClr = Bool()
    val clrPos = UInt(width = log2Up(txns))
    doAdd := Bool(false)
    doClr := Bool(false)

    // produce masks to allow simultaneous write+clear
    val writeMask = Mux(doAdd, UIntToOH(freePos), Bits(0, txns))
    val clearMask = Mux(doClr, ~(UIntToOH(clrPos)), ~Bits(0, txns))

    loadValid := (loadValid | writeMask) & clearMask

    // list of completed loads
    val completedQ = Module(new FPGAQueue(UInt(width = log2Up(txns)), 4)).io

    // ========================================================================

    // check incoming misses for line conflicts and tag match
    val missHead = io.misses.bits
    val lineMatch = (0 until txns).map(i => loadLine(i) === missHead.lineNum & loadValid(i))
    val tagMatch = (0 until txns).map(i => loadTag(i) === missHead.tag & loadValid(i))
    val tagMatchPos = PriorityEncoder(Vec(tagMatch).toBits)
    val isConflict = Vec(lineMatch).toBits.orR
    val isExisting = Vec(tagMatch).toBits.orR

    // conflicts cannot enter, even if there is a free slot
    val canEnter = isExisting | (!isConflict & hasFreeSlot)
    io.misses.ready := canEnter & missQEnq.ready & mreq.ready

    // move accepted miss into appropriate miss queue
    missQEnq.valid := io.misses.valid & canEnter
    missQEnq.bits := missHead
    missQEnqSel := Mux(isExisting, tagMatchPos, freePos)

    // prepare for issuing mem req for the missed cacheline
    mreq.valid := doAdd & !isExisting
    mreq.bits.channelID := UInt(chanIDBase) + freePos
    mreq.bits.isWrite := Bool(false)
    val theLine = Cat(missHead.tag, missHead.lineNum)
    mreq.bits.addr := io.contextBase + theLine * UInt(bytesPerLine)
    mreq.bits.numBytes := UInt(bytesPerLine)
    mreq.bits.metaData := UInt(0)

    when(io.misses.ready & io.misses.valid & !isExisting) {
      // add new miss entry into the table
      // write into CAM
      doAdd := Bool(true)
      loadLine(freePos) := missHead.lineNum
      loadTag(freePos) := missHead.tag
      loadPrg(freePos) := UInt(0)
    }

    // =======================================================================
    // handle read responses from main memory
    val mrspID = mrsp.bits.channelID - UInt(chanIDBase)
    val rspsReceived = loadPrg(mrspID)
    val targetLine = loadLine(mrspID)
    // signal ready to memRdRsp if line is not in use
    io.lineToCheck := targetLine
    mrsp.ready := !io.isLineInUse & completedQ.enq.ready
    // set up tag write
    io.tagPort.req.writeEn := Bool(false)
    io.tagPort.req.addr := targetLine
    io.tagPort.req.writeData := Cat(Bool(false), loadTag(mrspID))
    // set up data write
    io.dataPort.req.writeEn := Bool(false)
    val wAddrBase = targetLine * UInt(burstCount)
    val wAddrOffs = rspsReceived
    io.dataPort.req.addr := wAddrBase + wAddrOffs
    io.dataPort.req.writeData := mrsp.bits.readData
    // set up signals for completion
    completedQ.enq.bits := mrspID
    completedQ.enq.valid := Bool(false)

    when(mrsp.ready & mrsp.valid) {
      // activate data write
      io.dataPort.req.writeEn := Bool(true)
      // increment progress counter
      rspsReceived := rspsReceived + UInt(1)
      when(rspsReceived === UInt(0)) {
        // write valid = 0 when first response received
        io.tagPort.req.writeEn := Bool(true)
      } .elsewhen(rspsReceived === UInt(burstCount-1)) {
        // last burst beat received -- data is now valid
        io.tagPort.req.writeEn := Bool(true)
        io.tagPort.req.writeData := Cat(Bool(true), loadTag(mrspID))
        // signal completed, add mrspID to completedQ
        completedQ.enq.valid := Bool(true)
      }
    }

    // =======================================================================
    // flush pending requests when load finished
    val complHeadID = completedQ.deq.bits

    clrPos := complHeadID
    missQDeqSel := complHeadID

    io.resolved.valid := missQDeq.valid & completedQ.deq.valid
    io.resolved.bits := missQDeq.bits
    missQDeq.ready := io.resolved.ready & completedQ.deq.valid

    completedQ.deq.ready := Bool(false)

    when(completedQ.deq.valid & missQCounts(complHeadID) === UInt(0)) {
      completedQ.deq.ready := Bool(true)
      doClr := Bool(true)
    }

    // ==========================================================================
    // debug
    val verboseDebug = false
    if(verboseDebug) {
      when(io.misses.ready & io.misses.valid & !isExisting) {
        printf("New miss in handler: ")
        printf("miss id = %d line = %d tag = %d \n", freePos,
        missHead.lineNum, missHead.tag)
      }

      when(mrsp.ready & mrsp.valid) {
        printf("writing miss data, id %d addr %d data %d prg %d \n",
          mrspID, wAddrBase+wAddrOffs, mrsp.bits.readData, rspsReceived
        )
        when(rspsReceived === UInt(0)) {
          printf("invalidatig tag for line %d \n", targetLine)
        } .elsewhen(rspsReceived === UInt(burstCount-1)) {
          printf("signalling completion for line %d \n", targetLine)
        }
      }
    }
  }
}
