/*
 * gl_bridge.m — thin C wrappers for OpenGL ES 2.0 functions.
 *
 * Problem: on the iOS 26 simulator, OpenGLES.framework GL symbols (glViewport,
 * glClear, etc.) cannot be resolved via dlsym() at runtime.  The simulator uses
 * ANGLE / Metal stub dylibs where the symbols are present only as link-time stubs,
 * not as real run-time exports that dlsym() can find.
 *
 * Solution: compile these thin wrappers with Clang as part of the app binary.
 * Clang resolves the gl* symbols at link time from the simulator SDK stubs, so
 * the resulting forge_gl* wrappers live inside the main executable.
 *
 * Two mechanisms ensure dlsym(NULL,"forge_glViewport") succeeds at runtime:
 *
 *  1. __attribute__((used)) on every function: marks the .no_dead_strip flag
 *     in the object file so ld64 never removes them during -dead_strip, even
 *     though @Bridge/dlsym references them only by string (not by address).
 *
 *  2. -export_dynamic linker flag (in robovm-simulator.xml <ldFlags>): exports
 *     ALL global symbols to the dynamic linker table.  Without this, MobiVM's
 *     -exported_symbols_list restricts the dynamic table to RoboVM's own symbols,
 *     making forge_gl* private-extern (N_PEXT) and invisible to dlsym.
 *
 * Every wrapper has the exact ABI that RoboVM's @Bridge expects:
 *   int      → int
 *   float    → float
 *   boolean  → signed char (RoboVM BooleanMarshaler uses I8 / int8_t)
 *   Buffer   → void*  (RoboVM passes the buffer's native backing pointer)
 *   String   → const char*
 *   @Pointer long return → uintptr_t
 */

#include <OpenGLES/ES2/gl.h>
#include <OpenGLES/ES2/glext.h>
#include <stdint.h>

__attribute__((used)) void forge_glActiveTexture(int texture) { glActiveTexture(texture); }
__attribute__((used)) void forge_glAttachShader(int program, int shader) { glAttachShader(program, shader); }
__attribute__((used)) void forge_glBindAttribLocation(int program, int index, const char *name) { glBindAttribLocation(program, index, name); }
__attribute__((used)) void forge_glBindBuffer(int target, int buffer) { glBindBuffer(target, buffer); }
__attribute__((used)) void forge_glBindFramebuffer(int target, int framebuffer) { glBindFramebuffer(target, framebuffer); }
__attribute__((used)) void forge_glBindRenderbuffer(int target, int renderbuffer) { glBindRenderbuffer(target, renderbuffer); }
__attribute__((used)) void forge_glBindTexture(int target, int texture) { glBindTexture(target, texture); }
__attribute__((used)) void forge_glBlendColor(float red, float green, float blue, float alpha) { glBlendColor(red, green, blue, alpha); }
__attribute__((used)) void forge_glBlendEquation(int mode) { glBlendEquation(mode); }
__attribute__((used)) void forge_glBlendEquationSeparate(int modeRGB, int modeAlpha) { glBlendEquationSeparate(modeRGB, modeAlpha); }
__attribute__((used)) void forge_glBlendFunc(int sfactor, int dfactor) { glBlendFunc(sfactor, dfactor); }
__attribute__((used)) void forge_glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); }
__attribute__((used)) void forge_glBufferData(int target, int size, const void *data, int usage) { glBufferData(target, size, data, usage); }
__attribute__((used)) void forge_glBufferSubData(int target, int offset, int size, const void *data) { glBufferSubData(target, offset, size, data); }
__attribute__((used)) int  forge_glCheckFramebufferStatus(int target) { return (int)glCheckFramebufferStatus(target); }
__attribute__((used)) void forge_glClear(int mask) { glClear(mask); }
__attribute__((used)) void forge_glClearColor(float red, float green, float blue, float alpha) { glClearColor(red, green, blue, alpha); }
__attribute__((used)) void forge_glClearDepthf(float depth) { glClearDepthf(depth); }
__attribute__((used)) void forge_glClearStencil(int s) { glClearStencil(s); }
/* boolean is marshaled as signed char (I8) by RoboVM BooleanMarshaler */
__attribute__((used)) void forge_glColorMask(signed char red, signed char green, signed char blue, signed char alpha) { glColorMask(red, green, blue, alpha); }
__attribute__((used)) void forge_glCompileShader(int shader) { glCompileShader(shader); }
__attribute__((used)) void forge_glCompressedTexImage2D(int target, int level, int internalformat, int width, int height, int border, int imageSize, const void *data) { glCompressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data); }
__attribute__((used)) void forge_glCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int imageSize, const void *data) { glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data); }
__attribute__((used)) void forge_glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) { glCopyTexImage2D(target, level, internalformat, x, y, width, height, border); }
__attribute__((used)) void forge_glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) { glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height); }
__attribute__((used)) int  forge_glCreateProgram(void) { return (int)glCreateProgram(); }
__attribute__((used)) int  forge_glCreateShader(int type) { return (int)glCreateShader(type); }
__attribute__((used)) void forge_glCullFace(int mode) { glCullFace(mode); }
__attribute__((used)) void forge_glDeleteBuffers(int n, const int *buffers) { glDeleteBuffers(n, (const GLuint *)buffers); }
__attribute__((used)) void forge_glDeleteFramebuffers(int n, const int *framebuffers) { glDeleteFramebuffers(n, (const GLuint *)framebuffers); }
__attribute__((used)) void forge_glDeleteProgram(int program) { glDeleteProgram(program); }
__attribute__((used)) void forge_glDeleteRenderbuffers(int n, const int *renderbuffers) { glDeleteRenderbuffers(n, (const GLuint *)renderbuffers); }
__attribute__((used)) void forge_glDeleteShader(int shader) { glDeleteShader(shader); }
__attribute__((used)) void forge_glDeleteTextures(int n, const int *textures) { glDeleteTextures(n, (const GLuint *)textures); }
__attribute__((used)) void forge_glDepthFunc(int func) { glDepthFunc(func); }
__attribute__((used)) void forge_glDepthMask(signed char flag) { glDepthMask(flag); }
__attribute__((used)) void forge_glDepthRangef(float zNear, float zFar) { glDepthRangef(zNear, zFar); }
__attribute__((used)) void forge_glDetachShader(int program, int shader) { glDetachShader(program, shader); }
__attribute__((used)) void forge_glDisable(int cap) { glDisable(cap); }
__attribute__((used)) void forge_glDisableVertexAttribArray(int index) { glDisableVertexAttribArray(index); }
__attribute__((used)) void forge_glDrawArrays(int mode, int first, int count) { glDrawArrays(mode, first, count); }
/* Buffer version: RoboVM passes the buffer's native backing pointer as void* */
__attribute__((used)) void forge_glDrawElements(int mode, int count, int type, const void *indices) { glDrawElements(mode, count, type, indices); }
/* Integer offset version (VBO bound): cast int to void* */
__attribute__((used)) void forge_glDrawElementsI(int mode, int count, int type, int indices) { glDrawElements(mode, count, type, (const void *)(uintptr_t)(unsigned int)indices); }
__attribute__((used)) void forge_glEnable(int cap) { glEnable(cap); }
__attribute__((used)) void forge_glEnableVertexAttribArray(int index) { glEnableVertexAttribArray(index); }
__attribute__((used)) void forge_glFinish(void) { glFinish(); }
__attribute__((used)) void forge_glFlush(void) { glFlush(); }
__attribute__((used)) void forge_glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) { glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer); }
__attribute__((used)) void forge_glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) { glFramebufferTexture2D(target, attachment, textarget, texture, level); }
__attribute__((used)) void forge_glFrontFace(int mode) { glFrontFace(mode); }
__attribute__((used)) void forge_glGenBuffers(int n, int *buffers) { glGenBuffers(n, (GLuint *)buffers); }
__attribute__((used)) void forge_glGenerateMipmap(int target) { glGenerateMipmap(target); }
__attribute__((used)) void forge_glGenFramebuffers(int n, int *framebuffers) { glGenFramebuffers(n, (GLuint *)framebuffers); }
__attribute__((used)) void forge_glGenRenderbuffers(int n, int *renderbuffers) { glGenRenderbuffers(n, (GLuint *)renderbuffers); }
__attribute__((used)) void forge_glGenTextures(int n, int *textures) { glGenTextures(n, (GLuint *)textures); }
__attribute__((used)) void forge_glGetActiveAttrib(int program, int index, int bufSize, int *length, int *size, int *type, void *name) { glGetActiveAttrib(program, index, bufSize, length, size, (GLenum *)type, (GLchar *)name); }
__attribute__((used)) void forge_glGetActiveUniform(int program, int index, int bufSize, int *length, int *size, int *type, void *name) { glGetActiveUniform(program, index, bufSize, length, size, (GLenum *)type, (GLchar *)name); }
__attribute__((used)) void forge_glGetAttachedShaders(int program, int maxcount, void *count, int *shaders) { glGetAttachedShaders(program, maxcount, (GLsizei *)count, (GLuint *)shaders); }
__attribute__((used)) int  forge_glGetAttribLocation(int program, const char *name) { return glGetAttribLocation(program, name); }
__attribute__((used)) void forge_glGetBooleanv(int pname, void *params) { glGetBooleanv(pname, (GLboolean *)params); }
__attribute__((used)) void forge_glGetBufferParameteriv(int target, int pname, int *params) { glGetBufferParameteriv(target, pname, params); }
__attribute__((used)) int  forge_glGetError(void) { return (int)glGetError(); }
__attribute__((used)) void forge_glGetFloatv(int pname, float *params) { glGetFloatv(pname, params); }
__attribute__((used)) void forge_glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname, int *params) { glGetFramebufferAttachmentParameteriv(target, attachment, pname, params); }
__attribute__((used)) void forge_glGetIntegerv(int pname, int *params) { glGetIntegerv(pname, params); }
__attribute__((used)) void forge_glGetProgramInfoLog(int program, int bufSize, int *length, void *infoLog) { glGetProgramInfoLog(program, bufSize, length, (GLchar *)infoLog); }
__attribute__((used)) void forge_glGetProgramiv(int program, int pname, int *params) { glGetProgramiv(program, pname, params); }
__attribute__((used)) void forge_glGetRenderbufferParameteriv(int target, int pname, int *params) { glGetRenderbufferParameteriv(target, pname, params); }
__attribute__((used)) void forge_glGetShaderInfoLog(int shader, int bufSize, int *length, void *infoLog) { glGetShaderInfoLog(shader, bufSize, length, (GLchar *)infoLog); }
__attribute__((used)) void forge_glGetShaderiv(int shader, int pname, int *params) { glGetShaderiv(shader, pname, params); }
__attribute__((used)) void forge_glGetShaderPrecisionFormat(int shadertype, int precisiontype, int *range, int *precision) { glGetShaderPrecisionFormat(shadertype, precisiontype, range, precision); }
/* Returns a native pointer; Java side uses @Pointer long */
__attribute__((used)) uintptr_t forge_glGetString(int name) { return (uintptr_t)glGetString(name); }
__attribute__((used)) void forge_glGetTexParameterfv(int target, int pname, float *params) { glGetTexParameterfv(target, pname, params); }
__attribute__((used)) void forge_glGetTexParameteriv(int target, int pname, int *params) { glGetTexParameteriv(target, pname, params); }
__attribute__((used)) void forge_glGetUniformfv(int program, int location, float *params) { glGetUniformfv(program, location, params); }
__attribute__((used)) void forge_glGetUniformiv(int program, int location, int *params) { glGetUniformiv(program, location, params); }
__attribute__((used)) int  forge_glGetUniformLocation(int program, const char *name) { return glGetUniformLocation(program, name); }
__attribute__((used)) void forge_glGetVertexAttribfv(int index, int pname, float *params) { glGetVertexAttribfv(index, pname, params); }
__attribute__((used)) void forge_glGetVertexAttribiv(int index, int pname, int *params) { glGetVertexAttribiv(index, pname, params); }
__attribute__((used)) void forge_glHint(int target, int mode) { glHint(target, mode); }
__attribute__((used)) signed char forge_glIsBuffer(int buffer) { return (signed char)glIsBuffer(buffer); }
__attribute__((used)) signed char forge_glIsEnabled(int cap) { return (signed char)glIsEnabled(cap); }
__attribute__((used)) signed char forge_glIsFramebuffer(int framebuffer) { return (signed char)glIsFramebuffer(framebuffer); }
__attribute__((used)) signed char forge_glIsProgram(int program) { return (signed char)glIsProgram(program); }
__attribute__((used)) signed char forge_glIsRenderbuffer(int renderbuffer) { return (signed char)glIsRenderbuffer(renderbuffer); }
__attribute__((used)) signed char forge_glIsShader(int shader) { return (signed char)glIsShader(shader); }
__attribute__((used)) signed char forge_glIsTexture(int texture) { return (signed char)glIsTexture(texture); }
__attribute__((used)) void forge_glLineWidth(float width) { glLineWidth(width); }
__attribute__((used)) void forge_glLinkProgram(int program) { glLinkProgram(program); }
__attribute__((used)) void forge_glPixelStorei(int pname, int param) { glPixelStorei(pname, param); }
__attribute__((used)) void forge_glPolygonOffset(float factor, float units) { glPolygonOffset(factor, units); }
__attribute__((used)) void forge_glReadPixels(int x, int y, int width, int height, int format, int type, void *pixels) { glReadPixels(x, y, width, height, format, type, pixels); }
__attribute__((used)) void forge_glReleaseShaderCompiler(void) { glReleaseShaderCompiler(); }
__attribute__((used)) void forge_glRenderbufferStorage(int target, int internalformat, int width, int height) { glRenderbufferStorage(target, internalformat, width, height); }
__attribute__((used)) void forge_glSampleCoverage(float value, signed char invert) { glSampleCoverage(value, invert); }
__attribute__((used)) void forge_glScissor(int x, int y, int width, int height) { glScissor(x, y, width, height); }
/*
 * glShaderSource: the ByteBuffer passed from Java contains a native pointer
 * (uintptr_t) to the null-terminated UTF-8 shader string.  The C function
 * receives a void* pointing to that pointer, i.e. const char **.
 */
__attribute__((used)) void forge_glShaderSource(int shader, int count, const char **strings, const int *lengths) { glShaderSource(shader, count, strings, lengths); }
__attribute__((used)) void forge_glStencilFunc(int func, int ref, int mask) { glStencilFunc(func, ref, mask); }
__attribute__((used)) void forge_glStencilFuncSeparate(int face, int func, int ref, int mask) { glStencilFuncSeparate(face, func, ref, mask); }
__attribute__((used)) void forge_glStencilMask(int mask) { glStencilMask(mask); }
__attribute__((used)) void forge_glStencilMaskSeparate(int face, int mask) { glStencilMaskSeparate(face, mask); }
__attribute__((used)) void forge_glStencilOp(int fail, int zfail, int zpass) { glStencilOp(fail, zfail, zpass); }
__attribute__((used)) void forge_glStencilOpSeparate(int face, int fail, int zfail, int zpass) { glStencilOpSeparate(face, fail, zfail, zpass); }
__attribute__((used)) void forge_glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, const void *pixels) { glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels); }
__attribute__((used)) void forge_glTexParameterf(int target, int pname, float param) { glTexParameterf(target, pname, param); }
__attribute__((used)) void forge_glTexParameterfv(int target, int pname, const float *params) { glTexParameterfv(target, pname, params); }
__attribute__((used)) void forge_glTexParameteri(int target, int pname, int param) { glTexParameteri(target, pname, param); }
__attribute__((used)) void forge_glTexParameteriv(int target, int pname, const int *params) { glTexParameteriv(target, pname, (const GLint *)params); }
__attribute__((used)) void forge_glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, const void *pixels) { glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels); }
__attribute__((used)) void forge_glUniform1f(int location, float x) { glUniform1f(location, x); }
__attribute__((used)) void forge_glUniform1fv(int location, int count, const float *v) { glUniform1fv(location, count, v); }
__attribute__((used)) void forge_glUniform1i(int location, int x) { glUniform1i(location, x); }
__attribute__((used)) void forge_glUniform1iv(int location, int count, const int *v) { glUniform1iv(location, count, v); }
__attribute__((used)) void forge_glUniform2f(int location, float x, float y) { glUniform2f(location, x, y); }
__attribute__((used)) void forge_glUniform2fv(int location, int count, const float *v) { glUniform2fv(location, count, v); }
__attribute__((used)) void forge_glUniform2i(int location, int x, int y) { glUniform2i(location, x, y); }
__attribute__((used)) void forge_glUniform2iv(int location, int count, const int *v) { glUniform2iv(location, count, v); }
__attribute__((used)) void forge_glUniform3f(int location, float x, float y, float z) { glUniform3f(location, x, y, z); }
__attribute__((used)) void forge_glUniform3fv(int location, int count, const float *v) { glUniform3fv(location, count, v); }
__attribute__((used)) void forge_glUniform3i(int location, int x, int y, int z) { glUniform3i(location, x, y, z); }
__attribute__((used)) void forge_glUniform3iv(int location, int count, const int *v) { glUniform3iv(location, count, v); }
__attribute__((used)) void forge_glUniform4f(int location, float x, float y, float z, float w) { glUniform4f(location, x, y, z, w); }
__attribute__((used)) void forge_glUniform4fv(int location, int count, const float *v) { glUniform4fv(location, count, v); }
__attribute__((used)) void forge_glUniform4i(int location, int x, int y, int z, int w) { glUniform4i(location, x, y, z, w); }
__attribute__((used)) void forge_glUniform4iv(int location, int count, const int *v) { glUniform4iv(location, count, v); }
__attribute__((used)) void forge_glUniformMatrix2fv(int location, int count, signed char transpose, const float *value) { glUniformMatrix2fv(location, count, transpose, value); }
__attribute__((used)) void forge_glUniformMatrix3fv(int location, int count, signed char transpose, const float *value) { glUniformMatrix3fv(location, count, transpose, value); }
__attribute__((used)) void forge_glUniformMatrix4fv(int location, int count, signed char transpose, const float *value) { glUniformMatrix4fv(location, count, transpose, value); }
__attribute__((used)) void forge_glUseProgram(int program) { glUseProgram(program); }
__attribute__((used)) void forge_glValidateProgram(int program) { glValidateProgram(program); }
__attribute__((used)) void forge_glVertexAttrib1f(int indx, float x) { glVertexAttrib1f(indx, x); }
__attribute__((used)) void forge_glVertexAttrib1fv(int indx, const float *values) { glVertexAttrib1fv(indx, values); }
__attribute__((used)) void forge_glVertexAttrib2f(int indx, float x, float y) { glVertexAttrib2f(indx, x, y); }
__attribute__((used)) void forge_glVertexAttrib2fv(int indx, const float *values) { glVertexAttrib2fv(indx, values); }
__attribute__((used)) void forge_glVertexAttrib3f(int indx, float x, float y, float z) { glVertexAttrib3f(indx, x, y, z); }
__attribute__((used)) void forge_glVertexAttrib3fv(int indx, const float *values) { glVertexAttrib3fv(indx, values); }
__attribute__((used)) void forge_glVertexAttrib4f(int indx, float x, float y, float z, float w) { glVertexAttrib4f(indx, x, y, z, w); }
__attribute__((used)) void forge_glVertexAttrib4fv(int indx, const float *values) { glVertexAttrib4fv(indx, values); }
/* Buffer version: RoboVM passes the native buffer pointer as void* */
__attribute__((used)) void forge_glVertexAttribPointerB(int indx, int size, int type, signed char normalized, int stride, const void *ptr) { glVertexAttribPointer(indx, size, type, normalized, stride, ptr); }
/* Integer offset version (VBO bound): cast int to void* */
__attribute__((used)) void forge_glVertexAttribPointerI(int indx, int size, int type, signed char normalized, int stride, int ptr) { glVertexAttribPointer(indx, size, type, normalized, stride, (const void *)(uintptr_t)(unsigned int)ptr); }
__attribute__((used)) void forge_glViewport(int x, int y, int width, int height) { glViewport(x, y, width, height); }
