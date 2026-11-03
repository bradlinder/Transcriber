#ifndef GGML_ALLOC_H
#define GGML_ALLOC_H

#include "ggml.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ggml_gallocr * ggml_gallocr_t;

ggml_gallocr_t ggml_gallocr_new(enum ggml_type type);
void ggml_gallocr_free(ggml_gallocr_t galloc);

#ifdef __cplusplus
}
#endif

#endif // GGML_ALLOC_H
