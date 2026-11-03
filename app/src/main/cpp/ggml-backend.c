#include "ggml-backend.h"
#include <stdlib.h>

struct ggml_backend {
    int id;
};

ggml_backend_t ggml_backend_cpu_init(void) {
    struct ggml_backend * backend = (struct ggml_backend *)malloc(sizeof(struct ggml_backend));
    if (backend) {
        backend->id = 1;
    }
    return backend;
}

void ggml_backend_free(ggml_backend_t backend) {
    if (backend) {
        free(backend);
    }
}
