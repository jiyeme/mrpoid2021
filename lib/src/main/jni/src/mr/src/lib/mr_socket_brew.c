/*=========================================================================*\
* LuaSocket toolkit
* Networking support for the Lua language
* Diego Nehab
* 26/11/1999
*
* This library is part of an  effort to progressively increase the network
* connectivity  of  the Lua  language.  The  Lua interface  to  networking
* functions follows the Sockets API  closely, trying to simplify all tasks
* involved in setting up both  client and server connections. The provided
* IO routines, however, follow the Lua  style, being very similar  to the
* standard Lua read and write functions.
*
* RCS ID: $Id: luasocket.c,v 1.47 2005/01/02 22:51:32 diego Exp $
\*=========================================================================*/

/*=========================================================================*\
* Standard include files
\*=========================================================================*/
#include <mr.h>
#include <mr_auxlib.h>
#include "AEEStdLib.h"//ouli brew
//#include "compat-5.1.h"
#include "mr_socket_brew.h"

/*=========================================================================*\
* LuaSocket includes
\*=========================================================================*/

#include "auxiliar.h"
#include "mr_tcp_brew.h"
#include "mythroad_brew.h"
//#include "except.h"
//#include "timeout.h"
//#include "buffer.h"
//#include "inet.h"
//#include "tcp.h"
//#include "udp.h"
//#include "select.h"

/*-------------------------------------------------------------------------*\
* Internal function prototypes
\*-------------------------------------------------------------------------*/
static int global_skip(lua_State *L);
static int global_unload(lua_State *L);
static int base_open(lua_State *L);

/*-------------------------------------------------------------------------*\
* Modules and functions
\*-------------------------------------------------------------------------*/
static const luaL_reg mod[] = {
//    {"auxiliar", aux_open},
//    {"except", except_open},
//    {"timeout", tm_open},
//    {"buffer", buf_open},
//    {"inet", inet_open},
    {"tcp", tcp_open},
//    {"udp", udp_open},
//    {"select", select_open},
    {NULL, NULL}
};

static luaL_reg func[] = {
//    {"__gc",  global_unload},
    {"__unload",  global_unload},
    {"skip",      global_skip},
    {NULL,        NULL}
};


int socket_open(void) {
   int nResult;
   LegendGameApp *pLegendGame = (LegendGameApp *)GETAPPINSTANCE();
   
   nResult = ISHELL_CreateInstance(pLegendGame->a.m_pIShell, AEECLSID_NET, (void**)(&pLegendGame->m_piNet));
   if (nResult != SUCCESS) 
   {
      DBGPRINTF("Cannot create AEECLSID_NET!");
      return 0;
   }
   return 1;
}

/*-------------------------------------------------------------------------*\
* Close module 
\*-------------------------------------------------------------------------*/


/*-------------------------------------------------------------------------*\
* Skip a few arguments
\*-------------------------------------------------------------------------*/
static int global_skip(lua_State *L) {
    int amount = luaL_checkint(L, 1);
    int ret = lua_gettop(L) - amount - 1;
    return ret >= 0 ? ret : 0;
}

/*-------------------------------------------------------------------------*\
* Unloads the library
\*-------------------------------------------------------------------------*/
static int global_unload(lua_State *L) {
    LegendGameApp *pLegendGame = (LegendGameApp *)GETAPPINSTANCE();
    if(pLegendGame->m_piNet) 
    {
       INETMGR_Release(pLegendGame->m_piNet);
       pLegendGame->m_piNet = NULL;
    }
    
    return 0;
}

/*-------------------------------------------------------------------------*\
* Setup basic stuff.
\*-------------------------------------------------------------------------*/
static int base_open(lua_State *L) {
    if (socket_open()) {
        /* export functions (and leave namespace table on top of stack) */
        luaL_openlib(L, "socket", func, 0);
#ifdef LUASOCKET_DEBUG
        lua_pushstring(L, "DEBUG");
        lua_pushboolean(L, 1);
        lua_rawset(L, -3);
#endif
        /* make version string available to scripts */
        lua_pushstring(L, "VERSION");
        lua_pushstring(L, LUASOCKET_VERSION);
        lua_rawset(L, -3);
        return 1;
    } else {
        lua_pushstring(L, "unable to initialize library");
        lua_error(L);
        return 0;
    }
}

/*-------------------------------------------------------------------------*\
* Initializes all library modules.
\*-------------------------------------------------------------------------*/
LUASOCKET_API int mropen_socket(lua_State *L) {
    int i;
    base_open(L);
    for (i = 0; mod[i].name; i++) mod[i].func(L);
    return 1;
}
