#ifndef PMEMSTORE_H
#define PMEMSTORE_H

#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>

#include <string>
#include <iostream>
#include <cassert>
#include <atomic>

#include <libpmemobj.h>
#include <libcuckoo/cuckoohash_map.hh>

#include "xxhash.hpp"
#include "memory_interface.h"

#define PMEMKV_LAYOUT_NAME "pmemkv_layout"
#define MY_POOL_SIZE (70 * 1024 * 1024 * 1024) // 9G

// block header stored in pmem
struct block_hdr {
	void* next;
  uint64_t key;
	uint64_t size;
};

// block data entry stored in pmem
struct block_entry {
	struct block_hdr hdr;
  void* data;
};

// pmem root entry
struct base {
	block_entry* head;
	block_entry* tail;
  std::mutex rwlock;
	uint64_t bytes_written;
};

// block metadata stored in memory
struct block_meta {
  block_meta* next;
  uint64_t off;
  uint64_t size;
};

struct block_meta_list {
  block_meta* head;
  block_meta* tail;
  uint64_t total_size;
  uint64_t length;
};

// block data stored in memory 
struct memory_block {
  char* data;
  uint64_t size;
};

// block metadata stored in memory
struct memory_meta {
  uint64_t* meta;
  uint64_t length;
};

// pmem data allocation types
enum types {
  BLOCK_ENTRY_TYPE,
  DATA_TYPE,
  MAX_TYPE
};

/*
pmemkv data and index were stored in persistent memory.
data and index structure:
base[head,                                                       tail]
     |                                                            |
     block_entry_1[block_hdr[next, key, size], data]              |
                          |                                       |
                          block_entry_2[...[next...]]             |
                                         |                        |
                                         block_entry_3[...[next...]]

index map was stored in memory, rebuild index map when opening pmemkv
index structure:
key_1 --> block_meta_list_1[block_meta, block_meta, block_meta]
key_2 --> block_meta_list_2[block_meta, block_meta, block_meta]
key_3 --> block_meta_list_3[block_meta, block_meta, block_meta]
*/
class pmemkv {
  public:
    // explicit pmemkv(const char* dev_path_) : pmem_pool(nullptr), dev_path(dev_path_), bp(nullptr) {
    explicit pmemkv() :bp(nullptr) {
      // 不用创建pool
      create();
    }

    ~pmemkv() {
      close();
    }

    pmemkv(const pmemkv&) = delete;
    pmemkv& operator= (const pmemkv&) = delete;

    void TBPSet(uint64_t addr, uint64_t value) {
      uint64_t key = value;
      TBPStore(addr, &key, sizeof(uint64_t));
    }

    uint64_t TBPGet(uint64_t addr) {
      uint64_t ans;
      TBPLoad(addr, &ans, sizeof(uint64_t));
      return ans;
    }

    void TBPAdd(uint64_t addr, uint64_t value) {
      uint64_t tmp;
      TBPLoad(addr, &tmp, sizeof(uint64_t));
      tmp += value;
      TBPStore(addr, &tmp, sizeof(uint64_t));
    }

    void TBPSub(uint64_t addr, uint64_t value) {
      uint64_t tmp;
      TBPLoad(addr, &tmp, sizeof(uint64_t));
      tmp -= value;
      TBPStore(addr, &tmp, sizeof(uint64_t));
    }

    int put(std::string &key, const char* buf, const uint64_t count) {
      xxh::hash64_t key_i = xxh::xxhash<64>(key);
      // // set the return point
      // jmp_buf env;
      // if (setjmp(env)) {
      //   // end the transaction
      //   (void) pmemobj_tx_end();
      //   return -1;
      // }

      // allocate the new node to be inserted
      struct block_entry* bep = (struct block_entry*)TBPAlloc(sizeof(block_entry));
      TBPSet(uint64_t(&bep->data), TBPAlloc(count));
      TBPStore(uint64_t(bep->data), (void*)buf, count);
      TBPSet(uint64_t(&bep->hdr.next), 0);
      TBPSet(uint64_t(&bep->hdr.key), key_i);
      TBPSet(uint64_t(&bep->hdr.size), count);

      // add the modified root object to the undo data
      if(TBPGet(uint64_t(&bp->tail)) == 0) {
        // update head
        TBPSet(uint64_t(&bp->head), uint64_t(bep));
      } else {
        // add the modified tail entry to the undo data
        TBPSet(uint64_t(&bp->tail->hdr.next), uint64_t(bep));
      }
      TBPSet(uint64_t(&bp->tail), uint64_t(bep)); // update tail
      TBPAdd(uint64_t(&bp->bytes_written), count);

      // update in-memory index
      if (update_meta(bep)) {
        return -1;
      }
      return 0;
    }

    int get(std::string &key, struct memory_block *mb) {
      xxh::hash64_t key_i = xxh::xxhash<64>(key);
      if (!index_map.contains(key_i)) {
        perror("no such key in index_map");
        return -1;
      }
      // 这里的锁先不写，以后再说
      // if (pmemobj_rwlock_rdlock(pmem_pool, &bp->rwlock) != 0) {
		  //   return -1;
      // }
      struct block_meta_list* bml = NULL;
      index_map.find(key_i, bml);
      assert(bml != NULL);
      struct block_meta* bm = bml->head;
      uint64_t read_offset = 0;
      while (bm != NULL && (read_offset+bm->size <= mb->size)) {
        memcpy(mb->data+read_offset, (char*)bm->off, bm->size);
        read_offset += bm->size;
        assert(read_offset <= mb->size);
        bm = bm->next;
      }
      // pmemobj_rwlock_unlock(pmem_pool, &bp->rwlock);
      return 0;
    }

    int get_value_size(std::string &key, uint64_t* size) {
      xxh::hash64_t key_i = xxh::xxhash<64>(key);
      if (!index_map.contains(key_i)) {
        *size = 0;
        return -1;
      } else {
        struct block_meta_list* bml;
        index_map.find(key_i, bml);
        *size = bml->total_size;
        return 0;
      }
    }

    int get_meta(std::string &key, struct memory_meta* mm) {
      xxh::hash64_t key_i = xxh::xxhash<64>(key);
      if (!index_map.contains(key_i)) {
        mm = nullptr;
      } else {
        struct block_meta_list* bml;
        index_map.find(key_i, bml);
        uint64_t index = 0;
        struct block_meta *next = bml->head;
        while (next != nullptr) {
          assert(mm->meta != nullptr);
          mm->meta[index++] = next->off;
          mm->meta[index++] = next->size;
          next = next->next;
        }
        mm->length = index;
      }
      return 0;
    }

    int get_meta_size(std::string &key, uint64_t* size) {
      xxh::hash64_t key_i = xxh::xxhash<64>(key);
      if (!index_map.contains(key_i)) {
        *size = 0;
        return -1;
      } else {
        struct block_meta_list* bml;
        index_map.find(key_i, bml);
        *size = bml->length;
        return 0;
      }
    }
    // 遍历所有block_entry并输出
    int dump_all() {
      // 这里的锁可以之后再搞
      // if (pmemobj_rwlock_rdlock(pmem_pool, &bp->rwlock) != 0) {
		  //   return -1;
      // }
      struct block_entry* next_bep = (struct block_entry*)TBPGet(uint64_t(&bp->head));
      uint64_t read_offset = 0;
      while (uint64_t(next_bep) != 0) {
        uint64_t data_addr = TBPGet(uint64_t(&next_bep->data));
        uint64_t data_size = TBPGet(uint64_t(&next_bep->hdr.size));
        uint64_t data_key = TBPGet(uint64_t(&next_bep->hdr.key));
        uint64_t data_next = TBPGet(uint64_t(&next_bep->hdr.next));
        char* tmp = (char*)std::malloc(next_bep->hdr.size);
        TBPLoad(data_addr, tmp, data_size);
        std::cout << "key " << data_key << " value " << tmp << std::endl;
        read_offset += data_size;
        std::free(tmp);
        next_bep = (struct block_entry*)(data_next);
      }
      // pmemobj_rwlock_unlock(pmem_pool, &bp->rwlock);
      return 0; 
    }

    int dump_meta() {
      std::cout << "thnvm total bytes written " << TBPGet(uint64_t(&bp->bytes_written)) << std::endl;
      std::lock_guard<std::mutex> l(mtx);
      auto locked_index_map = index_map.lock_table();
      for (const auto &it : locked_index_map) {
        struct block_meta_list* bml = it.second;
        for (struct block_meta* ptr = bml->head; ptr != nullptr; ptr = ptr->next) {
          std::cout << "off " << ptr->off << " size " << ptr->size << std::endl;
        }
      }
      return 0;
    }
    // 遍历并释放非易失存储中的block entry list
    int free_all() {
      // struct block_entry* next_bep = bp->head;
      uint64_t next_bep = TBPGet(uint64_t(&bp->head));
      while (next_bep != 0) {
        struct block_entry* pre_bep = (struct block_entry*)next_bep;
        uint64_t data_size = TBPGet(uint64_t(&pre_bep->hdr.size));
        // TBPFree(&pre_bep->data, data_size);
        // TBPFree(next_bep, sizeof(struct block_entry));
        next_bep = TBPGet(uint64_t(&pre_bep->hdr.next));
        TBPSub(uint64_t(&bp->bytes_written), data_size);
      }
      // add root block to undo log
      TBPSet(uint64_t(&bp->head), 0);
      TBPSet(uint64_t(&bp->tail), 0);
      assert(TBPGet(uint64_t(&bp->bytes_written)) == 0);

      // free metadata
      if (free_meta()) {
        return -1;
      }

      return 0;
    }

    uint64_t get_root() {
      return (uint64_t)bp;      // 这里不确定！？？？？？？
      // return (uint64_t)pmem_pool;
    }
  private:
    void create() {
      std::cout << "Create kv" << std::endl;
      bp =  (struct base*)TBPAlloc(sizeof(base));
      TBPSet(uint64_t(&bp->head), 0);
      TBPSet(uint64_t(&bp->tail), 0);
      TBPSet(uint64_t(&bp->bytes_written), 0);
      std::cout << "Done Create kv" << std::endl;
    }

    int open() {
      return 0;
    }
    
    void close() {
      // pmemobj_close(pmem_pool);
      free_meta();
    }

    int free_meta() {
      std::lock_guard<std::mutex> l(mtx);
      // iterate index map, free all the in-memory index
      auto locked_index_map = index_map.lock_table();
      for (const auto &it : locked_index_map) {
        struct block_meta_list* bml = it.second;
        struct block_meta* cur = bml->head;
        while (cur != nullptr) {
          struct block_meta* next = cur->next;
          cur->next = nullptr;
          std::free(cur);
          bytes_allocated -= sizeof(block_meta);
          cur = next;
        }
        std::free(bml);
        bytes_allocated -= sizeof(block_meta_list);
        bml = nullptr;
      }
      locked_index_map.clear();
      assert(bytes_allocated == 0);
      return 0;
    }

    int update_meta(struct block_entry* bep) {
      std::lock_guard<std::mutex> l(mtx);
      if (!index_map.contains(bep->hdr.key)) {  // allocate new block_meta_list
        struct block_meta* bm = (struct block_meta*)std::malloc(sizeof(block_meta));
        if (!bm) {
          perror("malloc error in thnvm update_meta");
          return -1;
        }
        bytes_allocated += sizeof(block_meta);
        bm->off = (uint64_t)(bep->data);
        bm->size = bep->hdr.size;
        bm->next = nullptr;
        struct block_meta_list* bml = (struct block_meta_list*)std::malloc(sizeof(block_meta_list));
        if (!bml) {
          perror("malloc error in thnvm update_meta");
          return -1;
        }
        bytes_allocated += sizeof(block_meta_list);
        bml->head = bm;
        bml->tail = bml->head;
        bml->total_size = 0;
        bml->total_size += bm->size;
        bml->length = 0;
        bml->length += 1;
        index_map.insert(bep->hdr.key, bml);
      } else {   // append block_meta to existing block_meta_list
        struct block_meta_list* bml = nullptr;
        index_map.find(bep->hdr.key, bml);
        struct block_meta* bm = (struct block_meta*)std::malloc(sizeof(block_meta));
        if (!bm) {
          perror("malloc error in thnvm update_meta");
          return -1;
        }
        bytes_allocated += sizeof(block_meta);
        bm->off = (uint64_t)(bep->data);
        bm->size = bep->hdr.size;
        bm->next = nullptr;
        bml->tail->next = bm;
        bml->tail = bm;
        bml->total_size += bm->size;
        bml->length += 1;
      }
      return 0;
    }

  private:
    // PMEMobjpool* pmem_pool;
    const char* dev_path;
    struct base* bp;
    // PMEMoid bo;
    libcuckoo::cuckoohash_map<uint64_t, block_meta_list*> index_map;
    std::mutex mtx;
    std::atomic<uint64_t> bytes_allocated{0};
};

#endif
