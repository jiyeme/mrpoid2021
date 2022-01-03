#ifndef _MR_TYPES_H
#define _MR_TYPES_H

#ifndef SDK_MOD
typedef unsigned long long uint64; /* Unsigned 64 bit value */
typedef long long int64; /* signed 64 bit value */
#else
typedef unsigned _int64 uint64;
typedef _int64 int64;
#endif

typedef unsigned short uint16; /* Unsigned 16 bit value */
typedef unsigned long int uint32; /* Unsigned 32 bit value */
typedef long int int32; /* signed 32 bit value */
typedef unsigned char uint8; /*Unsigned  Signed 8  bit value */
typedef signed char int8; /* Signed 8  bit value */
typedef signed short int16; /* Signed 16 bit value */

typedef char * PSTR;
typedef const char * PCSTR;

#ifndef FALSE
#define FALSE 0
#endif

#ifndef TRUE
#define TRUE 1
#endif

#ifndef NULL 
#define NULL (void*)0
#endif

//typedef long int size_t;
typedef uint8 U8;
typedef unsigned int UINT;

#define MR_SUCCESS  0    //成功
#define MR_FAILED   -1    //失败
#define MR_IGNORE   1     //不关心
#define MR_WAITING   2     //异步(非阻塞)模式

#endif
