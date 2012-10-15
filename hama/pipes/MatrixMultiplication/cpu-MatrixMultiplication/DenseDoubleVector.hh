#include <string>

using std::string;

namespace math {

  class DenseDoubleVector {
  private:
      double *vector;
      int size;
  public:
    DenseDoubleVector(); // der Default-Konstruktor
    DenseDoubleVector(int length);
    /// Creates a new vector with the given length and default value.
    DenseDoubleVector(int length, double val); 
    // Creates a new vector with the given array.
    DenseDoubleVector(double arr[]);
    DenseDoubleVector(const string values);
    ~DenseDoubleVector();  // der Destruktor  
      
    int getLength();
    int getDimension();
    void set(int index, double value);
    double get(int index);
      
    DenseDoubleVector *add(DenseDoubleVector *v);
    DenseDoubleVector *add(double scalar);
    DenseDoubleVector *subtract(DenseDoubleVector *v);
    DenseDoubleVector *subtract(double v);
    DenseDoubleVector *subtractFrom(double v);
      
    DenseDoubleVector *multiply(double scalar);
    DenseDoubleVector *divide(double scalar);
    /*
    DenseDoubleVector *pow(int x);
    DenseDoubleVector *sqrt();
    */  
    double sum();
      
    //DenseDoubleVector *abs();
    double dot(DenseDoubleVector *s);
      
    double max();
    int maxIndex();
    double min();
    int minIndex();

    double *toArray();
    virtual string toString();
        
  };
    
}
