#include <stdbool.h>
#include <stdint.h>

/* No tracing collector is enabled. Android tracing calls are no-ops in this state. */
void ATrace_beginSection(const char *sectionName) {}
void ATrace_endSection(void) {}
void ATrace_beginAsyncSection(const char *sectionName, int32_t cookie) {}
void ATrace_endAsyncSection(const char *sectionName, int32_t cookie) {}
void ATrace_setCounter(const char *counterName, int64_t counterValue) {}

bool ATrace_isEnabled()
{
	return false;
}
