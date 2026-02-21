/*
 * gl_bridge.m — thin C wrappers for OpenGL ES 2.0 functions.
 *
 * Problem: on the iOS 26 simulator, OpenGLES.framework GL symbols (glViewport,
 * glClear, etc.) cannot be resolved via dlsym() at runtime.  The simulator uses
 * ANGLE / Metal stub dylibs where the symbols are present only as link-time stubs,
 * not as real run-time exports that dlsym() can find.
 *
 * Solution: compile these thin wrappers with Clang as part of the app binary
 * (via <objcSourceDirs> in robovm-simulator.xml).  Clang resolves the gl* symbols
 * at link time from the simulator SDK stubs, so the resulting forge_gl* wrappers
 * live inside the main executable and ARE findable by
 * dlsym(dlopen(NULL,...), "forge_glViewport") — i.e. @Library(Library.INTERNAL).
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

void forge_glActiveTexture(int texture) { glActiveTexture(texture); }
void forge_glAttachShader(int program, int shader) { glAttachShader(program, shader); }
void forge_glBindAttribLocation(int program, int index, const char *name) { glBindAttribLocation(program, index, name); }
void forge_glBindBuffer(int target, int buffer) { glBindBuffer(target, buffer); }
void forge_glBindFramebuffer(int target, int framebuffer) { glBindFramebuffer(target, framebuffer); }
void forge_glBindRenderbuffer(int target, int renderbuffer) { glBindRenderbuffer(target, renderbuffer); }
void forge_glBindTexture(int target, int texture) { glBindTexture(target, texture); }
void forge_glBlendColor(float red, float green, float blue, float alpha) { glBlendColor(red, green, blue, alpha); }
void forge_glBlendEquation(int mode) { glBlendEquation(mode); }
void forge_glBlendEquationSeparate(int modeRGB, int modeAlpha) { glBlendEquationSeparate(modeRGB, modeAlpha); }
void forge_glBlendFunc(int sfactor, int dfactor) { glBlendFunc(sfactor, dfactor); }
void forge_glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); }
void forge_glBufferData(int target, int size, const void *data, int usage) { glBufferData(target, size, data, usage); }
void forge_glBufferSubData(int target, int offset, int size, const void *data) { glBufferSubData(target, offset, size, data); }
int  forge_glCheckFramebufferStatus(int target) { return (int)glCheckFramebufferStatus(target); }
void forge_glClear(int mask) { glClear(mask); }
void forge_glClearColor(float red, float green, float blue, float alpha) { glClearColor(red, green, blue, alpha); }
void forge_glClearDepthf(float depth) { glClearDepthf(depth); }
void forge_glClearStencil(int s) { glClearStencil(s); }
/* boolean is marshaled as signed char (I8) by RoboVM BooleanMarshaler */
void forge_glColorMask(signed char red, signed char green, signed char blue, signed char alpha) { glColorMask(red, green, blue, alpha); }
void forge_glCompileShader(int shader) { glCompileShader(shader); }
void forge_glCompressedTexImage2D(int target, int level, int internalformat, int width, int height, int border, int imageSize, const void *data) { glCompressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data); }
void forge_glCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int imageSize, const void *data) { glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data); }
void forge_glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) { glCopyTexImage2D(target, level, internalformat, x, y, width, height, border); }
void forge_glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) { glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height); }
int  forge_glCreateProgram(void) { return (int)glCreateProgram(); }
int  forge_glCreateShader(int type) { return (int)glCreateShader(type); }
void forge_glCullFace(int mode) { glCullFace(mode); }
void forge_glDeleteBuffers(int n, const int *buffers) { glDeleteBuffers(n, (const GLuint *)buffers); }
void forge_glDeleteFramebuffers(int n, const int *framebuffers) { glDeleteFramebuffers(n, (const GLuint *)framebuffers); }
void forge_glDeleteProgram(int program) { glDeleteProgram(program); }
void forge_glDeleteRenderbuffers(int n, const int *renderbuffers) { glDeleteRenderbuffers(n, (const GLuint *)renderbuffers); }
void forge_glDeleteShader(int shader) { glDeleteShader(shader); }
void forge_glDeleteTextures(int n, const int *textures) { glDeleteTextures(n, (const GLuint *)textures); }
void forge_glDepthFunc(int func) { glDepthFunc(func); }
void forge_glDepthMask(signed char flag) { glDepthMask(flag); }
void forge_glDepthRangef(float zNear, float zFar) { glDepthRangef(zNear, zFar); }
void forge_glDetachShader(int program, int shader) { glDetachShader(program, shader); }
void forge_glDisable(int cap) { glDisable(cap); }
void forge_glDisableVertexAttribArray(int index) { glDisableVertexAttribArray(index); }
void forge_glDrawArrays(int mode, int first, int count) { glDrawArrays(mode, first, count); }
/* Buffer version: RoboVM passes the buffer's native backing pointer as void* */
void forge_glDrawElements(int mode, int count, int type, const void *indices) { glDrawElements(mode, count, type, indices); }
/* Integer offset version (VBO bound): cast int to void* */
void forge_glDrawElementsI(int mode, int count, int type, int indices) { glDrawElements(mode, count, type, (const void *)(uintptr_t)(unsigned int)indices); }
void forge_glEnable(int cap) { glEnable(cap); }
void forge_glEnableVertexAttribArray(int index) { glEnableVertexAttribArray(index); }
void forge_glFinish(void) { glFinish(); }
void forge_glFlush(void) { glFlush(); }
void forge_glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) { glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer); }
void forge_glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) { glFramebufferTexture2D(target, attachment, textarget, texture, level); }
void forge_glFrontFace(int mode) { glFrontFace(mode); }
void forge_glGenBuffers(int n, int *buffers) { glGenBuffers(n, (GLuint *)buffers); }
void forge_glGenerateMipmap(int target) { glGenerateMipmap(target); }
void forge_glGenFramebuffers(int n, int *framebuffers) { glGenFramebuffers(n, (GLuint *)framebuffers); }
void forge_glGenRenderbuffers(int n, int *renderbuffers) { glGenRenderbuffers(n, (GLuint *)renderbuffers); }
void forge_glGenTextures(int n, int *textures) { glGenTextures(n, (GLuint *)textures); }
void forge_glGetActiveAttrib(int program, int index, int bufSize, int *length, int *size, int *type, void *name) { glGetActiveAttrib(program, index, bufSize, length, size, (GLenum *)type, (GLchar *)name); }
void forge_glGetActiveUniform(int program, int index, int bufSize, int *length, int *size, int *type, void *name) { glGetActiveUniform(program, index, bufSize, length, size, (GLenum *)type, (GLchar *)name); }
void forge_glGetAttachedShaders(int program, int maxcount, void *count, int *shaders) { glGetAttachedShaders(program, maxcount, (GLsizei *)count, (GLuint *)shaders); }
int  forge_glGetAttribLocation(int program, const char *name) { return glGetAttribLocation(program, name); }
void forge_glGetBooleanv(int pname, void *params) { glGetBooleanv(pname, (GLboolean *)params); }
void forge_glGetBufferParameteriv(int target, int pname, int *params) { glGetBufferParameteriv(target, pname, params); }
int  forge_glGetError(void) { return (int)glGetError(); }
void forge_glGetFloatv(int pname, float *params) { glGetFloatv(pname, params); }
void forge_glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname, int *params) { glGetFramebufferAttachmentParameteriv(target, attachment, pname, params); }
void forge_glGetIntegerv(int pname, int *params) { glGetIntegerv(pname, params); }
void forge_glGetProgramInfoLog(int program, int bufSize, int *length, void *infoLog) { glGetProgramInfoLog(program, bufSize, length, (GLchar *)infoLog); }
void forge_glGetProgramiv(int program, int pname, int *params) { glGetProgramiv(program, pname, params); }
void forge_glGetRenderbufferParameteriv(int target, int pname, int *params) { glGetRenderbufferParameteriv(target, pname, params); }
void forge_glGetShaderInfoLog(int shader, int bufSize, int *length, void *infoLog) { glGetShaderInfoLog(shader, bufSize, length, (GLchar *)infoLog); }
void forge_glGetShaderiv(int shader, int pname, int *params) { glGetShaderiv(shader, pname, params); }
void forge_glGetShaderPrecisionFormat(int shadertype, int precisiontype, int *range, int *precision) { glGetShaderPrecisionFormat(shadertype, precisiontype, range, precision); }
/* Returns a native pointer; Java side uses @Pointer long */
uintptr_t forge_glGetString(int name) { return (uintptr_t)glGetString(name); }
void forge_glGetTexParameterfv(int target, int pname, float *params) { glGetTexParameterfv(target, pname, params); }
void forge_glGetTexParameteriv(int target, int pname, int *params) { glGetTexParameteriv(target, pname, params); }
void forge_glGetUniformfv(int program, int location, float *params) { glGetUniformfv(program, location, params); }
void forge_glGetUniformiv(int program, int location, int *params) { glGetUniformiv(program, location, params); }
int  forge_glGetUniformLocation(int program, const char *name) { return glGetUniformLocation(program, name); }
void forge_glGetVertexAttribfv(int index, int pname, float *params) { glGetVertexAttribfv(index, pname, params); }
void forge_glGetVertexAttribiv(int index, int pname, int *params) { glGetVertexAttribiv(index, pname, params); }
void forge_glHint(int target, int mode) { glHint(target, mode); }
signed char forge_glIsBuffer(int buffer) { return (signed char)glIsBuffer(buffer); }
signed char forge_glIsEnabled(int cap) { return (signed char)glIsEnabled(cap); }
signed char forge_glIsFramebuffer(int framebuffer) { return (signed char)glIsFramebuffer(framebuffer); }
signed char forge_glIsProgram(int program) { return (signed char)glIsProgram(program); }
signed char forge_glIsRenderbuffer(int renderbuffer) { return (signed char)glIsRenderbuffer(renderbuffer); }
signed char forge_glIsShader(int shader) { return (signed char)glIsShader(shader); }
signed char forge_glIsTexture(int texture) { return (signed char)glIsTexture(texture); }
void forge_glLineWidth(float width) { glLineWidth(width); }
void forge_glLinkProgram(int program) { glLinkProgram(program); }
void forge_glPixelStorei(int pname, int param) { glPixelStorei(pname, param); }
void forge_glPolygonOffset(float factor, float units) { glPolygonOffset(factor, units); }
void forge_glReadPixels(int x, int y, int width, int height, int format, int type, void *pixels) { glReadPixels(x, y, width, height, format, type, pixels); }
void forge_glReleaseShaderCompiler(void) { glReleaseShaderCompiler(); }
void forge_glRenderbufferStorage(int target, int internalformat, int width, int height) { glRenderbufferStorage(target, internalformat, width, height); }
void forge_glSampleCoverage(float value, signed char invert) { glSampleCoverage(value, invert); }
void forge_glScissor(int x, int y, int width, int height) { glScissor(x, y, width, height); }
/*
 * glShaderSource: the ByteBuffer passed from Java contains a native pointer
 * (uintptr_t) to the null-terminated UTF-8 shader string.  The C function
 * receives a void* pointing to that pointer, i.e. const char **.
 */
void forge_glShaderSource(int shader, int count, const char **strings, const int *lengths) { glShaderSource(shader, count, strings, lengths); }
void forge_glStencilFunc(int func, int ref, int mask) { glStencilFunc(func, ref, mask); }
void forge_glStencilFuncSeparate(int face, int func, int ref, int mask) { glStencilFuncSeparate(face, func, ref, mask); }
void forge_glStencilMask(int mask) { glStencilMask(mask); }
void forge_glStencilMaskSeparate(int face, int mask) { glStencilMaskSeparate(face, mask); }
void forge_glStencilOp(int fail, int zfail, int zpass) { glStencilOp(fail, zfail, zpass); }
void forge_glStencilOpSeparate(int face, int fail, int zfail, int zpass) { glStencilOpSeparate(face, fail, zfail, zpass); }
void forge_glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, const void *pixels) { glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels); }
void forge_glTexParameterf(int target, int pname, float param) { glTexParameterf(target, pname, param); }
void forge_glTexParameterfv(int target, int pname, const float *params) { glTexParameterfv(target, pname, params); }
void forge_glTexParameteri(int target, int pname, int param) { glTexParameteri(target, pname, param); }
void forge_glTexParameteriv(int target, int pname, const int *params) { glTexParameteriv(target, pname, (const GLint *)params); }
void forge_glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, const void *pixels) { glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels); }
void forge_glUniform1f(int location, float x) { glUniform1f(location, x); }
void forge_glUniform1fv(int location, int count, const float *v) { glUniform1fv(location, count, v); }
void forge_glUniform1i(int location, int x) { glUniform1i(location, x); }
void forge_glUniform1iv(int location, int count, const int *v) { glUniform1iv(location, count, v); }
void forge_glUniform2f(int location, float x, float y) { glUniform2f(location, x, y); }
void forge_glUniform2fv(int location, int count, const float *v) { glUniform2fv(location, count, v); }
void forge_glUniform2i(int location, int x, int y) { glUniform2i(location, x, y); }
void forge_glUniform2iv(int location, int count, const int *v) { glUniform2iv(location, count, v); }
void forge_glUniform3f(int location, float x, float y, float z) { glUniform3f(location, x, y, z); }
void forge_glUniform3fv(int location, int count, const float *v) { glUniform3fv(location, count, v); }
void forge_glUniform3i(int location, int x, int y, int z) { glUniform3i(location, x, y, z); }
void forge_glUniform3iv(int location, int count, const int *v) { glUniform3iv(location, count, v); }
void forge_glUniform4f(int location, float x, float y, float z, float w) { glUniform4f(location, x, y, z, w); }
void forge_glUniform4fv(int location, int count, const float *v) { glUniform4fv(location, count, v); }
void forge_glUniform4i(int location, int x, int y, int z, int w) { glUniform4i(location, x, y, z, w); }
void forge_glUniform4iv(int location, int count, const int *v) { glUniform4iv(location, count, v); }
void forge_glUniformMatrix2fv(int location, int count, signed char transpose, const float *value) { glUniformMatrix2fv(location, count, transpose, value); }
void forge_glUniformMatrix3fv(int location, int count, signed char transpose, const float *value) { glUniformMatrix3fv(location, count, transpose, value); }
void forge_glUniformMatrix4fv(int location, int count, signed char transpose, const float *value) { glUniformMatrix4fv(location, count, transpose, value); }
void forge_glUseProgram(int program) { glUseProgram(program); }
void forge_glValidateProgram(int program) { glValidateProgram(program); }
void forge_glVertexAttrib1f(int indx, float x) { glVertexAttrib1f(indx, x); }
void forge_glVertexAttrib1fv(int indx, const float *values) { glVertexAttrib1fv(indx, values); }
void forge_glVertexAttrib2f(int indx, float x, float y) { glVertexAttrib2f(indx, x, y); }
void forge_glVertexAttrib2fv(int indx, const float *values) { glVertexAttrib2fv(indx, values); }
void forge_glVertexAttrib3f(int indx, float x, float y, float z) { glVertexAttrib3f(indx, x, y, z); }
void forge_glVertexAttrib3fv(int indx, const float *values) { glVertexAttrib3fv(indx, values); }
void forge_glVertexAttrib4f(int indx, float x, float y, float z, float w) { glVertexAttrib4f(indx, x, y, z, w); }
void forge_glVertexAttrib4fv(int indx, const float *values) { glVertexAttrib4fv(indx, values); }
/* Buffer version: RoboVM passes the native buffer pointer as void* */
void forge_glVertexAttribPointerB(int indx, int size, int type, signed char normalized, int stride, const void *ptr) { glVertexAttribPointer(indx, size, type, normalized, stride, ptr); }
/* Integer offset version (VBO bound): cast int to void* */
void forge_glVertexAttribPointerI(int indx, int size, int type, signed char normalized, int stride, int ptr) { glVertexAttribPointer(indx, size, type, normalized, stride, (const void *)(uintptr_t)(unsigned int)ptr); }
void forge_glViewport(int x, int y, int width, int height) { glViewport(x, y, width, height); }
