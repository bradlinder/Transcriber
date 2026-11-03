#ifndef GGML_BACKEND_H
#define GGML_BACKEND_H

#include "ggml.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ggml_backend * ggml_backend_t;

ggml_backend_t ggml_backend_cpu_init(void);
void ggml_backend_free(ggml_backend_t backend);

#ifdef __cplusplus
}
#endif

#endif // GGML_BACKEND_H
