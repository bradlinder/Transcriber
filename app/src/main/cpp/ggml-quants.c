#include "ggml-quants.h"
#include <string.h>

void dequantize_row_q4_0(const void * vx, float * y, int k) {
    if (vx && y && k > 0) {
        memset(y, 0, k * sizeof(float));
    }
}

void dequantize_row_q8_0(const void * vx, float * y, int k) {
    if (vx && y && k > 0) {
        memset(y, 0, k * sizeof(float));
    }
}
