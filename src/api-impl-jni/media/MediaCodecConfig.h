/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ATL_MEDIA_CODEC_CONFIG_H
#define ATL_MEDIA_CODEC_CONFIG_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

static inline bool atl_copy_codec_config(uint8_t *destination, size_t destination_size,
                                         size_t offset, const uint8_t *source,
                                         size_t source_size)
{
	if ((!destination && destination_size != 0) || (!source && source_size != 0) ||
	    offset > destination_size || source_size > destination_size - offset)
		return false;
	if (source_size != 0)
		memcpy(destination + offset, source, source_size);
	return true;
}

#endif
