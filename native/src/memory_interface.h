
#ifndef __MEMORY_INTERFACE_H__
#define __MEMORY_INTERFACE_H__

#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <stdio.h>
#include <stdint.h>


#ifdef __cplusplus
extern "C" {
#endif

/**
 * alloc persistent memory space
 * return address, 0 on fail
 */
uint64_t TBPAlloc(uint64_t size);
/**
 * load persistent memory content into buffer
 * return 0 on success; -1 fail
 */
int TBPLoad(uint64_t global_address, void *buffer, uint64_t size);
/**
 * store buffer content into persistent memory content
 * return 0 on success; -1 fail
 */
int TBPStore(uint64_t global_address, void *buffer, uint64_t size);
// /**
//  * free persistent memory given address
//  * return 0 on success; -1 fail
//  */
int TBPfree(uint64_t global_address, uint64_t size);

#ifdef __cplusplus
}
#endif
#endif


