#include "hadoop/StringUtils.hh"
#include "DenseDoubleVector.hh"
#include <limits>
#include <string>
#include <iostream>

using std::string;
using std::cout;

namespace math {
  
  DenseDoubleVector::DenseDoubleVector(int len) : size(len), vector(new double[len]) { 
  }

  DenseDoubleVector::DenseDoubleVector(int len, double val) : size(len), vector(new double[len]) {
    for (int i=0; i<len; i++)
      vector[i] = val;
  }
    
  DenseDoubleVector::DenseDoubleVector(double arr[]) : vector(arr) { 
  }

  DenseDoubleVector::DenseDoubleVector(const string values) { 
    cout << "DenseDoubleVector values: " << values << "\n";
  }

  DenseDoubleVector::~DenseDoubleVector() { 
    free(vector);
  }
    
  int DenseDoubleVector::getLength() {
    return size;
  }

  int DenseDoubleVector::getDimension() {
    return getLength();
  }

  void DenseDoubleVector::set(int index, double value) {
    vector[index] = value;
  }
    
  double DenseDoubleVector::get(int index) {
    return vector[index];
  }

  DenseDoubleVector* DenseDoubleVector::add(DenseDoubleVector *v) {
    DenseDoubleVector *newv = new DenseDoubleVector(v->getLength());
    for (int i = 0; i < v->getLength(); i++) {
      newv->set(i, this->get(i) + v->get(i));
    }
    return newv;
  }
  
  DenseDoubleVector* DenseDoubleVector::add(double scalar) {
    DenseDoubleVector *newv = new DenseDoubleVector(this->getLength());
    for (int i = 0; i < this->getLength(); i++) {
      newv->set(i, this->get(i) + scalar);
    }
    return newv;
  }

  DenseDoubleVector* DenseDoubleVector::subtract(DenseDoubleVector *v) {
    DenseDoubleVector *newv = new DenseDoubleVector(v->getLength());
    for (int i = 0; i < v->getLength(); i++) {
      newv->set(i, this->get(i) - v->get(i));
    }
    return newv;
  }
    
  DenseDoubleVector* DenseDoubleVector::subtract(double v) {
    DenseDoubleVector *newv = new DenseDoubleVector(size);
    for (int i = 0; i < size; i++) {
      newv->set(i, vector[i] - v);
    }
    return newv;
  }
    
  DenseDoubleVector* DenseDoubleVector::subtractFrom(double v) {
    DenseDoubleVector *newv = new DenseDoubleVector(size);
    for (int i = 0; i < size; i++) {
      newv->set(i, v - vector[i]);
    }
    return newv;
  }

  DenseDoubleVector* DenseDoubleVector::multiply(double scalar) {
    DenseDoubleVector *v = new DenseDoubleVector(size);
    for (int i = 0; i < size; i++) {
      v->set(i, this->get(i) * scalar);
    }
    return v;
  }

  DenseDoubleVector* DenseDoubleVector::divide(double scalar) {
    DenseDoubleVector *v = new DenseDoubleVector(size);
    for (int i = 0; i < size; i++) {
      v->set(i, this->get(i) / scalar);
    }
    return v;
  }
 
    /*
  DenseDoubleVector* pow(int x) {
    DenseDoubleVector *v = new DenseDoubleVector(size);
    for (int i = 0; i < size; i++) {
      double value = 0.0;
      // it is faster to multiply when we having ^2
      if (x == 2) {
        value = vector[i] * vector[i];
      }
      else {
        value = pow(vector[i], x);
      }
      v->set(i, value);
    }
    return v;
  }
    
  DenseDoubleVector* sqrt() {
    DenseDoubleVector *v = new DenseDoubleVector(size);
    for (int i = 0; i < size; i++) {
      v->set(i, sqrt(vector[i]));
    }
    return v;
  }
        */
     
  double DenseDoubleVector::sum() {
    double sum = 0.0;
    for (int i = 0; i < size; i++) {
      sum +=  vector[i];
    }
    return sum;
  }
   
  /*  
  DenseDoubleVector* abs() {
    DenseDoubleVector *v = new DenseDoubleVector(size);
    for (int i = 0; i < size; i++) {
      v->set(i, abs(vector[i]));
    }
    return v;
  }
*/

  double DenseDoubleVector::dot(DenseDoubleVector *s) {
    double dotProduct = 0.0;
    for (int i = 0; i < size; i++) {
      dotProduct += this->get(i) * s->get(i);
    }
    return dotProduct;
  }
    
  double DenseDoubleVector::max() {
    double max = std::numeric_limits<double>::min();
    for (int i = 0; i < size; i++) {
      double d = vector[i];
      if (d > max) {
        max = d;
      }
    }
    return max;
  }
    
  int DenseDoubleVector::maxIndex() {
    double max = std::numeric_limits<double>::min();
    int maxIndex = 0;
    for (int i = 0; i < size; i++) {
      double d = vector[i];
      if (d > max) {
        max = d;
        maxIndex = i;
      }
    }
    return maxIndex;
  }
    
  double DenseDoubleVector::min() {
    double min = std::numeric_limits<double>::max();
    for (int i = 0; i < size; i++) {
      double d = vector[i];
      if (d < min) {
        min = d;
      }
    }
    return min;
  }

  int DenseDoubleVector::minIndex() {
    double min = std::numeric_limits<double>::max();
    int minIndex = 0;
    for (int i = 0; i < size; i++) {
      double d = vector[i];
      if (d < min) {
        min = d;
        minIndex = i;
      }
    }
    return minIndex;
  }
    
  double* DenseDoubleVector::toArray() {
    return vector;
  }
    
  string DenseDoubleVector::toString() {
    string str;
    for (int i = 0; i < size; i++)
        str += HadoopUtils::toString(vector[i]);
          
    return str;
  }
       
} //namespace math
