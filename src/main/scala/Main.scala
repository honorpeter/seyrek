package Seyrek

import Chisel._
import TidbitsTestbenches._
import TidbitsOCM._
import TidbitsStreams._
import TidbitsSimUtils._
import TidbitsAXI._
import TidbitsDMA._
import TidbitsPlatformWrapper._
import TidbitsMath._

object ChannelConfigs {
  val csrTest = Map(
    "ptrs" -> ReadChanParams(maxReadTxns = 8, port = 2),
    "inds" -> ReadChanParams(maxReadTxns = 8, port = 3),
    "nzdata" -> ReadChanParams(maxReadTxns = 8, port = 0),
    "inpvec" -> ReadChanParams(maxReadTxns = 8, port = 1)
  )
}

class CSRTestParams(p: PlatformWrapperParams) extends SeyrekParams {
  val accelName = "CSRTest"
  val numPEs = 1
  val portsPerPE = 4
  val chanConfig = ChannelConfigs.csrTest
  val indWidth = 32
  val valWidth = 64
  val mrp = p.toMemReqParams()

  val makeSemiringAdd = { () =>
    new StagedUIntOp(valWidth, 1, {(a: UInt, b: UInt) => a+b})
  }

  val makeSemiringMul = { () =>
    new SystolicSInt64Mul_5Stage()
  }
  val issueWindow = 8
}

object SeyrekMainObj {
  type AccelInstFxn = PlatformWrapperParams => SpMVAccelCSR
  type AccelMap = Map[String, AccelInstFxn]
  type PlatformInstFxn = AccelInstFxn => PlatformWrapper
  type PlatformMap = Map[String, PlatformInstFxn]

  val accelMap: AccelMap  = Map(
    "CSRTest" -> {p => new SpMVAccelCSR(p, new CSRTestParams(p))}
  )

  val platformMap: PlatformMap = Map(
    "ZedBoard" -> {f => new ZedBoardWrapper(f)},
    "WX690T" -> {f => new WolverinePlatformWrapper(f)},
    "Tester" -> {f => new TesterWrapper(f)}
  )

  def fileCopy(from: String, to: String) = {
    import java.io.{File,FileInputStream,FileOutputStream}
    val src = new File(from)
    val dest = new File(to)
    new FileOutputStream(dest) getChannel() transferFrom(
      new FileInputStream(src) getChannel, 0, Long.MaxValue )
  }

  def makeVerilog(args: Array[String]) = {
    val accelName = args(0)
    val platformName = args(1)
    val accInst = accelMap(accelName)
    val platformInst = platformMap(platformName)
    val chiselArgs = Array("--backend", "v")

    chiselMain(chiselArgs, () => Module(platformInst(accInst)))
  }

  def makeEmulator(args: Array[String]) = {
    val accelName = args(0)

    val accInst = accelMap(accelName)
    val platformInst = platformMap("Tester")
    val chiselArgs = Array("--backend","c","--targetDir", "emulator")

    chiselMain(chiselArgs, () => Module(platformInst(accInst)))
    // build driver
    platformInst(accInst).generateRegDriver("emulator/")
    accInst(TesterWrapperParams).generatePerfCtrMapCode("emulator/")
    // copy emulator driver and SW support files
    val regDrvRoot = "src/main/scala/fpga-tidbits/platform-wrapper/regdriver/"
    val files = Array("wrapperregdriver.h", "platform-tester.cpp",
      "platform.h", "testerdriver.hpp")
    for(f <- files) { fileCopy(regDrvRoot + f, "emulator/" + f) }
    // copy Seyrek support files
    val seyrekDrvRoot = "src/main/cpp/"
    val seyrekFiles = Array("commonsemirings.hpp", "HWCSRSpMV.hpp",
      "semiring.hpp", "wrapperregdriver.h", "CSR.hpp", "main-csr.cpp",
      "CSRSpMV.hpp", "platform.h", "SWCSRSpMV.hpp", "seyrek-tester.cpp",
      "seyrekconsts.hpp", "ParallelHWCSRSpMV.hpp")
    for(f <- seyrekFiles) { fileCopy(seyrekDrvRoot + f, "emulator/" + f) }
  }

  def makeDriver(args: Array[String]) = {
    val accelName = args(0)
    val platformName = args(1)
    val accInst = accelMap(accelName)
    val platformInst = platformMap(platformName)

    platformInst(accInst).generateRegDriver(".")
    accInst(TesterWrapperParams).generatePerfCtrMapCode(".")
  }

  def showHelp() = {
    println("Usage: run <op> <accel> <platform>")
    println("where:")
    println("<op> = verilog driver emulator")
    println("<accel> = " + accelMap.keys.reduce({_ + " " +_}))
    println("<platform> = " + platformMap.keys.reduce({_ + " " +_}))
  }

  def main(args: Array[String]): Unit = {
    if (args.size != 3) {
      showHelp()
      return
    }

    val op = args(0)
    val rst = args.drop(1)

    if (op == "verilog" || op == "v") {
      makeVerilog(rst)
    } else if (op == "driver" || op == "d") {
      makeDriver(rst)
    } else if (op == "emulator" || op == "e") {
      makeEmulator(rst)
    } else {
      showHelp()
      return
    }
  }
}
