#ifndef GGML_H
#define GGML_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GGML_MAX_DIMS 4

enum ggml_type {
    GGML_TYPE_F32  = 0,
    GGML_TYPE_F16  = 1,
    GGML_TYPE_Q4_0 = 2,
    GGML_TYPE_Q4_1 = 3,
    GGML_TYPE_Q8_0 = 8,
    GGML_TYPE_I8   = 9,
    GGML_TYPE_I16  = 10,
    GGML_TYPE_I32  = 11,
    GGML_TYPE_COUNT,
};

struct ggml_object;
struct ggml_context;

struct ggml_tensor {
    enum ggml_type type;
    int n_dims;
    int64_t ne[GGML_MAX_DIMS];
    size_t nb[GGML_MAX_DIMS];
    void * data;
    char name[64];
};

struct ggml_init_params {
    size_t mem_size;
    void * mem_buffer;
    bool   no_alloc;
};

struct ggml_context * ggml_init(struct ggml_init_params params);
void ggml_free(struct ggml_context * ctx);

size_t ggml_type_size(enum ggml_type type);
size_t ggml_element_size(const struct ggml_tensor * tensor);

#ifdef __cplusplus
}
#endif

#endif // GGML_H
