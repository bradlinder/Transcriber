#ifndef GGML_QUANTS_H
#define GGML_QUANTS_H

#include "ggml.h"

#ifdef __cplusplus
extern "C" {
#endif

void dequantize_row_q4_0(const void * vx, float * y, int k);
void dequantize_row_q8_0(const void * vx, float * y, int k);

#ifdef __cplusplus
}
#endif

#endif // GGML_QUANTS_H
