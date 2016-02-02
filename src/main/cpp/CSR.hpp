#ifndef CSR_H_
#define CSR_H

#include <string.h>
#include <string>
#include <vector>

extern void * readMatrixData(std::string name, std::string component);

typedef struct {
  unsigned int rows;
  unsigned int cols;
  unsigned int nz;
  unsigned int startingRow;
  unsigned int startingCol;
  unsigned int bytesPerInd;
  unsigned int bytesPerVal;
} SparseMatrixMetadata;


template <class SpMVInd, class SpMVVal>
class CSR {
public:

  CSR() {
    m_needsDealloc = false;
    m_metadata = 0;
    m_indPtrs = 0; m_inds = 0; m_nzData = 0;
    m_name = "<not initialized>";
  }

  virtual ~CSR(){
    if(m_needsDealloc) {
      delete m_metadata;
      delete [] m_indPtrs;
      delete [] m_inds;
      delete [] m_nzData;
    }
  }

  void printSummary() {
    std::cout << "Matrix summary" << std::endl;
    std::cout << "name = " << m_name << std::endl;
    std::cout << "#rows = " << m_metadata->rows << std::endl;
    std::cout << "#cols = " << m_metadata->cols << std::endl;
    std::cout << "#nz = " << m_metadata->nz << std::endl;
  }

  bool isSquare(){
    return m_metadata->cols == m_metadata->rows;
  }

  void setName(std::string name) {m_name = name;}
  std::string getName() {return m_name;}

  static CSR * load(std::string name, bool genNZData = false) {
    SparseMatrixMetadata * md = (SparseMatrixMetadata *)readMatrixData(name, "meta");
    if(md->bytesPerInd != sizeof(SpMVInd)) {
        throw "bytesPerInd mismatch in CSR::load";
    }
    if((md->bytesPerVal != sizeof(SpMVVal)) && !genNZData) {
        throw "bytesPerVal mismatch in CSR::load -- consider using genNZData";
    }
    CSR * ret = new CSR();
    ret->m_metadata = md;
    ret->m_indPtrs = (SpMVInd *)readMatrixData(name, "indptr");
    if(!ret->m_indPtrs) throw "could not load indptr in CSR::load";
    ret->m_inds = (SpMVInd *)readMatrixData(name, "inds");
    if(!ret->m_inds) throw "could not load inds in CSR::load";
    if(genNZData) {
      std::cout << "Warning: not using original matrix nzdata values" << std::endl;
      SpMVInd nnz = md->nz;
      ret->m_nzData = new SpMVVal[nnz];
      for(SpMVInd i = 0; i < nnz; i++) {
        ret->m_nzData[i] = (SpMVVal) (i + 1);
      }
    } else {
      ret->m_nzData = (SpMVVal *)readMatrixData(name, "nzdata");
      if(!ret->m_nzData) throw "could not load nzdata in CSR::load";
    }

    ret->m_name = name;

    ret->m_needsDealloc = true;

    return ret;
  }

  static CSR * eye(unsigned int dim) {
    CSR * ret = new CSR();
    ret->m_metadata = new SparseMatrixMetadata;
    ret->m_metadata->startingRow = 0;
    ret->m_metadata->startingCol = 0;
    ret->m_metadata->cols = dim;
    ret->m_metadata->rows = dim;
    ret->m_metadata->nz = dim;
    ret->m_indPtrs = new SpMVInd[dim+1];
    ret->m_inds = new SpMVInd[dim];
    ret->m_nzData = new SpMVVal[dim];

    for(SpMVInd i = 0; i < dim; i++) {
        ret->m_indPtrs[i] = i;
        ret->m_inds[i] = i;
        ret->m_nzData[i] = 1; // TODO this should actually come from the semiring
    }
    ret->m_indPtrs[dim] = dim;
    ret->setName("eye");

    ret->m_needsDealloc = true;

    return ret;
  }

  static CSR * dense(unsigned int dim) {
    CSR * ret = new CSR();
    ret->m_metadata = new SparseMatrixMetadata;
    ret->m_metadata->startingRow = 0;
    ret->m_metadata->startingCol = 0;
    ret->m_metadata->cols = dim;
    ret->m_metadata->rows = dim;
    ret->m_metadata->nz = dim*dim;
    ret->m_indPtrs = new SpMVInd[dim+1];
    ret->m_inds = new SpMVInd[dim*dim];
    ret->m_nzData = new SpMVVal[dim*dim];

    for(SpMVInd i = 0; i < dim; i++)
      ret->m_indPtrs[i] = i*dim;
    ret->m_indPtrs[dim] = dim*dim;

    for(SpMVInd i = 0; i < dim*dim; i++) {
        ret->m_inds[i] = i % dim;
        ret->m_nzData[i] = i+1;
    }

    ret->setName("dense");

    ret->m_needsDealloc = true;

    return ret;
  }

  unsigned int getCols() const {
    return m_metadata->cols;
  }

  SpMVInd* getIndPtrs() const {
    return m_indPtrs;
  }

  SpMVInd* getInds() const {
    return m_inds;
  }

  unsigned int getNNZ() const {
    return m_metadata->nz;
  }

  SpMVVal* getNZData() const {
    return m_nzData;
  }

  unsigned int getRows() const {
    return m_metadata->rows;
  }

  unsigned int getStartingRow() const {
    return m_metadata->startingRow;
  }

  // calculate a vector of row indices that split the matrix into equal-sized
  // horizontal chunks
  std::vector<SpMVInd> calcRowPartitionBoundaries(unsigned int numPartitions) {
    std::vector<SpMVInd> boundaries;
    unsigned int divSize = (m_metadata->rows + numPartitions) / numPartitions;
    for(unsigned int i = 0; i < numPartitions; i++) {
      boundaries.push_back(divSize * i);
    }
    // push the matrix nz count as the upper bound
    boundaries.push_back(m_metadata->rows);
    return boundaries;
  }


  std::vector<CSR<SpMVInd, SpMVVal> * > rowPartitionedView(std::vector<SpMVInd> boundaries) {
    std::vector<CSR<SpMVInd, SpMVVal> * > res;
    unsigned int numPartitions = boundaries.size() - 1;
    for(unsigned int i = 0; i < numPartitions; i++) {
        res.push_back(new CSR<SpMVInd, SpMVVal>());
        res[i]->m_metadata = new SparseMatrixMetadata;
        res[i]->m_metadata->cols = m_metadata->cols;
        res[i]->m_metadata->rows = boundaries[i+1] - boundaries[i];
        res[i]->m_metadata->nz = m_indPtrs[boundaries[i+1]] - m_indPtrs[boundaries[i]];
        res[i]->m_metadata->startingRow = boundaries[i];
        res[i]->m_metadata->startingCol = 0;
        res[i]->m_metadata->bytesPerInd = m_metadata->bytesPerInd;
        res[i]->m_metadata->bytesPerVal = m_metadata->bytesPerVal;
        // note: this will only work for hardware -- software needs the untouched
        // indPtr/inds/nzdata pointers
        res[i]->m_indPtrs = (SpMVInd *) &m_indPtrs[boundaries[i]];
        res[i]->m_inds = (SpMVInd *) &m_inds[m_indPtrs[boundaries[i]]];
        res[i]->m_nzData = (SpMVVal *) &m_nzData[m_indPtrs[boundaries[i]]];
        char partName[256];
        itoa(i, partName);
        res[i]->m_name = m_name + "-p" + std::string(partName);
    }
    return res;
  }


protected:
  bool m_needsDealloc;
  SparseMatrixMetadata * m_metadata;

  SpMVInd * m_indPtrs;
  SpMVInd * m_inds;
  SpMVVal * m_nzData;
  std::string m_name;

  /* itoa:  convert n to characters in s */
  static void itoa(int n, char s[])
  {
      int i, sign;

      if ((sign = n) < 0)  /* record sign */
          n = -n;          /* make n positive */
      i = 0;
      do {       /* generate digits in reverse order */
          s[i++] = n % 10 + '0';   /* get next digit */
      } while ((n /= 10) > 0);     /* delete it */
      if (sign < 0)
          s[i++] = '-';
      s[i] = '\0';
      reverse(s);
  }

  static void reverse(char s[])
   {
       int i, j;
       char c;

       for (i = 0, j = strlen(s)-1; i<j; i++, j--) {
           c = s[i];
           s[i] = s[j];
           s[j] = c;
       }
   }

};

#endif
