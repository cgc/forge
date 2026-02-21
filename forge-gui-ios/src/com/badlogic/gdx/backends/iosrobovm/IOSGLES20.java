/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

/*
 * Simulator override of IOSGLES20.
 *
 * The stock IOSGLES20 from gdx-backend-robovm uses native (JNI) methods whose
 * implementations live in libgdx.a.  libgdx.a contains only armv7 and arm64
 * *device* slices and cannot be linked into an arm64-simulator binary.
 *
 * This replacement uses RoboVM @Bridge annotations to call the OpenGL ES C
 * functions directly via the already-linked OpenGLES framework, without any
 * JNI wrappers from libgdx.a.
 *
 * Key constraint: RoboVM's @Bridge processor requires bridge methods to be
 * *static* when calling plain C functions (the compiler cannot marshal the
 * Java `this` reference as a C function parameter).  All bridge methods are
 * therefore declared as private static native, and the GL20 interface is
 * fulfilled by non-static wrappers that delegate to them.
 */

package com.badlogic.gdx.backends.iosrobovm;

import java.nio.*;

import com.badlogic.gdx.graphics.GL20;
import org.robovm.apple.foundation.NSProcessInfo;
import org.robovm.rt.VM;
import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Pointer;

// @Library("OpenGLES") tells RoboVM to open OpenGLES.framework at class-init
// time via dlopen.  Bro.bind() then looks up each @Bridge symbol (e.g.
// "glViewport") in that handle via dlsym.  OpenGLES.framework (ANGLE on
// iOS 26 simulator) exports all standard GL ES 2.0 function names.
@Library("OpenGLES")
public class IOSGLES20 implements GL20 {

    static {
        // Bro.bind() walks every @Bridge method in this class and calls
        // dlsym(OpenGLES_handle, symbol) for each one, storing the function
        // address so that subsequent @Bridge calls dispatch to it directly.
        // Without this call the bridge pointers stay null and every @Bridge
        // invocation throws UnsatisfiedLinkError "@Bridge method … not bound".
        Bro.bind(IOSGLES20.class);
    }

    final boolean shouldConvert16bit = IOSApplication.IS_METALANGLE
        && NSProcessInfo.getSharedProcessInfo().getEnvironment().containsKey("SIMULATOR_DEVICE_NAME");

    public IOSGLES20 () {
    }

    /** Last viewport set; GLKView resets the viewport on each draw call. */
    public static int x, y, width, height;

    // -------------------------------------------------------------------------
    // Static @Bridge declarations — each maps to a C function in OpenGLES.framework
    // -------------------------------------------------------------------------

    @Bridge(symbol = "glActiveTexture")
    private static native void _glActiveTexture(int texture);

    @Bridge(symbol = "glAttachShader")
    private static native void _glAttachShader(int program, int shader);

    @Bridge(symbol = "glBindAttribLocation")
    private static native void _glBindAttribLocation(int program, int index, String name);

    @Bridge(symbol = "glBindBuffer")
    private static native void _glBindBuffer(int target, int buffer);

    @Bridge(symbol = "glBindFramebuffer")
    private static native void _glBindFramebuffer(int target, int framebuffer);

    @Bridge(symbol = "glBindRenderbuffer")
    private static native void _glBindRenderbuffer(int target, int renderbuffer);

    @Bridge(symbol = "glBindTexture")
    private static native void _glBindTexture(int target, int texture);

    @Bridge(symbol = "glBlendColor")
    private static native void _glBlendColor(float red, float green, float blue, float alpha);

    @Bridge(symbol = "glBlendEquation")
    private static native void _glBlendEquation(int mode);

    @Bridge(symbol = "glBlendEquationSeparate")
    private static native void _glBlendEquationSeparate(int modeRGB, int modeAlpha);

    @Bridge(symbol = "glBlendFunc")
    private static native void _glBlendFunc(int sfactor, int dfactor);

    @Bridge(symbol = "glBlendFuncSeparate")
    private static native void _glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);

    @Bridge(symbol = "glBufferData")
    private static native void _glBufferData(int target, int size, Buffer data, int usage);

    @Bridge(symbol = "glBufferSubData")
    private static native void _glBufferSubData(int target, int offset, int size, Buffer data);

    @Bridge(symbol = "glCheckFramebufferStatus")
    private static native int _glCheckFramebufferStatus(int target);

    @Bridge(symbol = "glClear")
    private static native void _glClear(int mask);

    @Bridge(symbol = "glClearColor")
    private static native void _glClearColor(float red, float green, float blue, float alpha);

    @Bridge(symbol = "glClearDepthf")
    private static native void _glClearDepthf(float depth);

    @Bridge(symbol = "glClearStencil")
    private static native void _glClearStencil(int s);

    @Bridge(symbol = "glColorMask")
    private static native void _glColorMask(boolean red, boolean green, boolean blue, boolean alpha);

    @Bridge(symbol = "glCompileShader")
    private static native void _glCompileShader(int shader);

    @Bridge(symbol = "glCompressedTexImage2D")
    private static native void _glCompressedTexImage2D(int target, int level, int internalformat,
        int width, int height, int border, int imageSize, Buffer data);

    @Bridge(symbol = "glCompressedTexSubImage2D")
    private static native void _glCompressedTexSubImage2D(int target, int level, int xoffset,
        int yoffset, int width, int height, int format, int imageSize, Buffer data);

    @Bridge(symbol = "glCopyTexImage2D")
    private static native void _glCopyTexImage2D(int target, int level, int internalformat,
        int x, int y, int width, int height, int border);

    @Bridge(symbol = "glCopyTexSubImage2D")
    private static native void _glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset,
        int x, int y, int width, int height);

    @Bridge(symbol = "glCreateProgram")
    private static native int _glCreateProgram();

    @Bridge(symbol = "glCreateShader")
    private static native int _glCreateShader(int type);

    @Bridge(symbol = "glCullFace")
    private static native void _glCullFace(int mode);

    @Bridge(symbol = "glDeleteBuffers")
    private static native void _glDeleteBuffers(int n, IntBuffer buffers);

    @Bridge(symbol = "glDeleteFramebuffers")
    private static native void _glDeleteFramebuffers(int n, IntBuffer framebuffers);

    @Bridge(symbol = "glDeleteProgram")
    private static native void _glDeleteProgram(int program);

    @Bridge(symbol = "glDeleteRenderbuffers")
    private static native void _glDeleteRenderbuffers(int n, IntBuffer renderbuffers);

    @Bridge(symbol = "glDeleteShader")
    private static native void _glDeleteShader(int shader);

    @Bridge(symbol = "glDeleteTextures")
    private static native void _glDeleteTextures(int n, IntBuffer textures);

    @Bridge(symbol = "glDepthFunc")
    private static native void _glDepthFunc(int func);

    @Bridge(symbol = "glDepthMask")
    private static native void _glDepthMask(boolean flag);

    @Bridge(symbol = "glDepthRangef")
    private static native void _glDepthRangef(float zNear, float zFar);

    @Bridge(symbol = "glDetachShader")
    private static native void _glDetachShader(int program, int shader);

    @Bridge(symbol = "glDisable")
    private static native void _glDisable(int cap);

    @Bridge(symbol = "glDisableVertexAttribArray")
    private static native void _glDisableVertexAttribArray(int index);

    @Bridge(symbol = "glDrawArrays")
    private static native void _glDrawArrays(int mode, int first, int count);

    @Bridge(symbol = "glDrawElements")
    private static native void _glDrawElementsB(int mode, int count, int type, Buffer indices);

    // VBO offset variant: pass the byte-offset integer as a const void* (zero-extended to 64-bit).
    @Bridge(symbol = "glDrawElements")
    private static native void _glDrawElementsI(int mode, int count, int type, @Pointer long indices);

    @Bridge(symbol = "glEnable")
    private static native void _glEnable(int cap);

    @Bridge(symbol = "glEnableVertexAttribArray")
    private static native void _glEnableVertexAttribArray(int index);

    @Bridge(symbol = "glFinish")
    private static native void _glFinish();

    @Bridge(symbol = "glFlush")
    private static native void _glFlush();

    @Bridge(symbol = "glFramebufferRenderbuffer")
    private static native void _glFramebufferRenderbuffer(int target, int attachment,
        int renderbuffertarget, int renderbuffer);

    @Bridge(symbol = "glFramebufferTexture2D")
    private static native void _glFramebufferTexture2D(int target, int attachment, int textarget,
        int texture, int level);

    @Bridge(symbol = "glFrontFace")
    private static native void _glFrontFace(int mode);

    @Bridge(symbol = "glGenBuffers")
    private static native void _glGenBuffers(int n, IntBuffer buffers);

    @Bridge(symbol = "glGenerateMipmap")
    private static native void _glGenerateMipmap(int target);

    @Bridge(symbol = "glGenFramebuffers")
    private static native void _glGenFramebuffers(int n, IntBuffer framebuffers);

    @Bridge(symbol = "glGenRenderbuffers")
    private static native void _glGenRenderbuffers(int n, IntBuffer renderbuffers);

    @Bridge(symbol = "glGenTextures")
    private static native void _glGenTextures(int n, IntBuffer textures);

    @Bridge(symbol = "glGetActiveAttrib")
    private static native void _glGetActiveAttrib(int program, int index, int bufSize,
        IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name);

    @Bridge(symbol = "glGetActiveUniform")
    private static native void _glGetActiveUniform(int program, int index, int bufSize,
        IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name);

    @Bridge(symbol = "glGetAttachedShaders")
    private static native void _glGetAttachedShaders(int program, int maxcount, Buffer count, IntBuffer shaders);

    @Bridge(symbol = "glGetAttribLocation")
    private static native int _glGetAttribLocation(int program, String name);

    @Bridge(symbol = "glGetBooleanv")
    private static native void _glGetBooleanv(int pname, Buffer params);

    @Bridge(symbol = "glGetBufferParameteriv")
    private static native void _glGetBufferParameteriv(int target, int pname, IntBuffer params);

    @Bridge(symbol = "glGetError")
    private static native int _glGetError();

    @Bridge(symbol = "glGetFloatv")
    private static native void _glGetFloatv(int pname, FloatBuffer params);

    @Bridge(symbol = "glGetFramebufferAttachmentParameteriv")
    private static native void _glGetFramebufferAttachmentParameteriv(int target, int attachment,
        int pname, IntBuffer params);

    @Bridge(symbol = "glGetIntegerv")
    private static native void _glGetIntegerv(int pname, IntBuffer params);

    @Bridge(symbol = "glGetProgramInfoLog")
    private static native void _glGetProgramInfoLog(int program, int bufSize, IntBuffer length, ByteBuffer infoLog);

    @Bridge(symbol = "glGetProgramiv")
    private static native void _glGetProgramiv(int program, int pname, IntBuffer params);

    @Bridge(symbol = "glGetRenderbufferParameteriv")
    private static native void _glGetRenderbufferParameteriv(int target, int pname, IntBuffer params);

    @Bridge(symbol = "glGetShaderInfoLog")
    private static native void _glGetShaderInfoLog(int shader, int bufSize, IntBuffer length, ByteBuffer infoLog);

    @Bridge(symbol = "glGetShaderiv")
    private static native void _glGetShaderiv(int shader, int pname, IntBuffer params);

    @Bridge(symbol = "glGetShaderPrecisionFormat")
    private static native void _glGetShaderPrecisionFormat(int shadertype, int precisiontype,
        IntBuffer range, IntBuffer precision);

    @Bridge(symbol = "glGetString")
    private static native @Pointer long _glGetString(int name);

    @Bridge(symbol = "glGetTexParameterfv")
    private static native void _glGetTexParameterfv(int target, int pname, FloatBuffer params);

    @Bridge(symbol = "glGetTexParameteriv")
    private static native void _glGetTexParameteriv(int target, int pname, IntBuffer params);

    @Bridge(symbol = "glGetUniformfv")
    private static native void _glGetUniformfv(int program, int location, FloatBuffer params);

    @Bridge(symbol = "glGetUniformiv")
    private static native void _glGetUniformiv(int program, int location, IntBuffer params);

    @Bridge(symbol = "glGetUniformLocation")
    private static native int _glGetUniformLocation(int program, String name);

    @Bridge(symbol = "glGetVertexAttribfv")
    private static native void _glGetVertexAttribfv(int index, int pname, FloatBuffer params);

    @Bridge(symbol = "glGetVertexAttribiv")
    private static native void _glGetVertexAttribiv(int index, int pname, IntBuffer params);

    @Bridge(symbol = "glHint")
    private static native void _glHint(int target, int mode);

    @Bridge(symbol = "glIsBuffer")
    private static native boolean _glIsBuffer(int buffer);

    @Bridge(symbol = "glIsEnabled")
    private static native boolean _glIsEnabled(int cap);

    @Bridge(symbol = "glIsFramebuffer")
    private static native boolean _glIsFramebuffer(int framebuffer);

    @Bridge(symbol = "glIsProgram")
    private static native boolean _glIsProgram(int program);

    @Bridge(symbol = "glIsRenderbuffer")
    private static native boolean _glIsRenderbuffer(int renderbuffer);

    @Bridge(symbol = "glIsShader")
    private static native boolean _glIsShader(int shader);

    @Bridge(symbol = "glIsTexture")
    private static native boolean _glIsTexture(int texture);

    @Bridge(symbol = "glLineWidth")
    private static native void _glLineWidth(float width);

    @Bridge(symbol = "glLinkProgram")
    private static native void _glLinkProgram(int program);

    @Bridge(symbol = "glPixelStorei")
    private static native void _glPixelStorei(int pname, int param);

    @Bridge(symbol = "glPolygonOffset")
    private static native void _glPolygonOffset(float factor, float units);

    @Bridge(symbol = "glReadPixels")
    private static native void _glReadPixels(int x, int y, int width, int height, int format, int type, Buffer pixels);

    @Bridge(symbol = "glReleaseShaderCompiler")
    private static native void _glReleaseShaderCompiler();

    @Bridge(symbol = "glRenderbufferStorage")
    private static native void _glRenderbufferStorage(int target, int internalformat, int width, int height);

    @Bridge(symbol = "glSampleCoverage")
    private static native void _glSampleCoverage(float value, boolean invert);

    @Bridge(symbol = "glScissor")
    private static native void _glScissor(int x, int y, int width, int height);

    // glShaderSource expects const char** for its third argument.  RoboVM has no
    // built-in marshaler for String[], so we pass a ByteBuffer whose contents are
    // the native pointer(s) to the null-terminated UTF-8 string(s).
    @Bridge(symbol = "glShaderSource")
    private static native void _glShaderSource(int shader, int count, ByteBuffer stringPtrs, IntBuffer length);

    @Bridge(symbol = "glStencilFunc")
    private static native void _glStencilFunc(int func, int ref, int mask);

    @Bridge(symbol = "glStencilFuncSeparate")
    private static native void _glStencilFuncSeparate(int face, int func, int ref, int mask);

    @Bridge(symbol = "glStencilMask")
    private static native void _glStencilMask(int mask);

    @Bridge(symbol = "glStencilMaskSeparate")
    private static native void _glStencilMaskSeparate(int face, int mask);

    @Bridge(symbol = "glStencilOp")
    private static native void _glStencilOp(int fail, int zfail, int zpass);

    @Bridge(symbol = "glStencilOpSeparate")
    private static native void _glStencilOpSeparate(int face, int fail, int zfail, int zpass);

    @Bridge(symbol = "glTexImage2D")
    private static native void _glTexImage2D(int target, int level, int internalformat, int width,
        int height, int border, int format, int type, Buffer pixels);

    @Bridge(symbol = "glTexParameterf")
    private static native void _glTexParameterf(int target, int pname, float param);

    @Bridge(symbol = "glTexParameterfv")
    private static native void _glTexParameterfv(int target, int pname, FloatBuffer params);

    @Bridge(symbol = "glTexParameteri")
    private static native void _glTexParameteri(int target, int pname, int param);

    @Bridge(symbol = "glTexParameteriv")
    private static native void _glTexParameteriv(int target, int pname, IntBuffer params);

    @Bridge(symbol = "glTexSubImage2D")
    private static native void _glTexSubImage2D(int target, int level, int xoffset, int yoffset,
        int width, int height, int format, int type, Buffer pixels);

    @Bridge(symbol = "glUniform1f")
    private static native void _glUniform1f(int location, float x);

    @Bridge(symbol = "glUniform1fv")
    private static native void _glUniform1fv(int location, int count, FloatBuffer v);

    @Bridge(symbol = "glUniform1i")
    private static native void _glUniform1i(int location, int x);

    @Bridge(symbol = "glUniform1iv")
    private static native void _glUniform1iv(int location, int count, IntBuffer v);

    @Bridge(symbol = "glUniform2f")
    private static native void _glUniform2f(int location, float x, float y);

    @Bridge(symbol = "glUniform2fv")
    private static native void _glUniform2fv(int location, int count, FloatBuffer v);

    @Bridge(symbol = "glUniform2i")
    private static native void _glUniform2i(int location, int x, int y);

    @Bridge(symbol = "glUniform2iv")
    private static native void _glUniform2iv(int location, int count, IntBuffer v);

    @Bridge(symbol = "glUniform3f")
    private static native void _glUniform3f(int location, float x, float y, float z);

    @Bridge(symbol = "glUniform3fv")
    private static native void _glUniform3fv(int location, int count, FloatBuffer v);

    @Bridge(symbol = "glUniform3i")
    private static native void _glUniform3i(int location, int x, int y, int z);

    @Bridge(symbol = "glUniform3iv")
    private static native void _glUniform3iv(int location, int count, IntBuffer v);

    @Bridge(symbol = "glUniform4f")
    private static native void _glUniform4f(int location, float x, float y, float z, float w);

    @Bridge(symbol = "glUniform4fv")
    private static native void _glUniform4fv(int location, int count, FloatBuffer v);

    @Bridge(symbol = "glUniform4i")
    private static native void _glUniform4i(int location, int x, int y, int z, int w);

    @Bridge(symbol = "glUniform4iv")
    private static native void _glUniform4iv(int location, int count, IntBuffer v);

    @Bridge(symbol = "glUniformMatrix2fv")
    private static native void _glUniformMatrix2fv(int location, int count, boolean transpose, FloatBuffer value);

    @Bridge(symbol = "glUniformMatrix3fv")
    private static native void _glUniformMatrix3fv(int location, int count, boolean transpose, FloatBuffer value);

    @Bridge(symbol = "glUniformMatrix4fv")
    private static native void _glUniformMatrix4fv(int location, int count, boolean transpose, FloatBuffer value);

    @Bridge(symbol = "glUseProgram")
    private static native void _glUseProgram(int program);

    @Bridge(symbol = "glValidateProgram")
    private static native void _glValidateProgram(int program);

    @Bridge(symbol = "glVertexAttrib1f")
    private static native void _glVertexAttrib1f(int indx, float x);

    @Bridge(symbol = "glVertexAttrib1fv")
    private static native void _glVertexAttrib1fv(int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttrib2f")
    private static native void _glVertexAttrib2f(int indx, float x, float y);

    @Bridge(symbol = "glVertexAttrib2fv")
    private static native void _glVertexAttrib2fv(int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttrib3f")
    private static native void _glVertexAttrib3f(int indx, float x, float y, float z);

    @Bridge(symbol = "glVertexAttrib3fv")
    private static native void _glVertexAttrib3fv(int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttrib4f")
    private static native void _glVertexAttrib4f(int indx, float x, float y, float z, float w);

    @Bridge(symbol = "glVertexAttrib4fv")
    private static native void _glVertexAttrib4fv(int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttribPointer")
    private static native void _glVertexAttribPointerB(int indx, int size, int type,
        boolean normalized, int stride, Buffer ptr);

    // VBO offset variant: pass the byte-offset integer as a const void* (zero-extended to 64-bit).
    @Bridge(symbol = "glVertexAttribPointer")
    private static native void _glVertexAttribPointerI(int indx, int size, int type,
        boolean normalized, int stride, @Pointer long ptr);

    @Bridge(symbol = "glViewport")
    private static native void _glViewport(int x, int y, int width, int height);

    // -------------------------------------------------------------------------
    // GL20 interface implementations — delegate to static @Bridge methods above
    // -------------------------------------------------------------------------

    @Override public void glActiveTexture(int texture) { _glActiveTexture(texture); }
    @Override public void glAttachShader(int program, int shader) { _glAttachShader(program, shader); }
    @Override public void glBindAttribLocation(int program, int index, String name) { _glBindAttribLocation(program, index, name); }
    @Override public void glBindBuffer(int target, int buffer) { _glBindBuffer(target, buffer); }
    @Override public void glBindFramebuffer(int target, int framebuffer) { _glBindFramebuffer(target, framebuffer); }
    @Override public void glBindRenderbuffer(int target, int renderbuffer) { _glBindRenderbuffer(target, renderbuffer); }
    @Override public void glBindTexture(int target, int texture) { _glBindTexture(target, texture); }
    @Override public void glBlendColor(float red, float green, float blue, float alpha) { _glBlendColor(red, green, blue, alpha); }
    @Override public void glBlendEquation(int mode) { _glBlendEquation(mode); }
    @Override public void glBlendEquationSeparate(int modeRGB, int modeAlpha) { _glBlendEquationSeparate(modeRGB, modeAlpha); }
    @Override public void glBlendFunc(int sfactor, int dfactor) { _glBlendFunc(sfactor, dfactor); }
    @Override public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { _glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); }
    @Override public void glBufferData(int target, int size, Buffer data, int usage) { _glBufferData(target, size, data, usage); }
    @Override public void glBufferSubData(int target, int offset, int size, Buffer data) { _glBufferSubData(target, offset, size, data); }
    @Override public int glCheckFramebufferStatus(int target) { return _glCheckFramebufferStatus(target); }
    @Override public void glClear(int mask) { _glClear(mask); }
    @Override public void glClearColor(float red, float green, float blue, float alpha) { _glClearColor(red, green, blue, alpha); }
    @Override public void glClearDepthf(float depth) { _glClearDepthf(depth); }
    @Override public void glClearStencil(int s) { _glClearStencil(s); }
    @Override public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) { _glColorMask(red, green, blue, alpha); }
    @Override public void glCompileShader(int shader) { _glCompileShader(shader); }

    @Override
    public void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height,
        int border, int imageSize, Buffer data) {
        _glCompressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data);
    }

    @Override
    public void glCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width,
        int height, int format, int imageSize, Buffer data) {
        _glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data);
    }

    @Override
    public void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {
        _glCopyTexImage2D(target, level, internalformat, x, y, width, height, border);
    }

    @Override
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        _glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    @Override public int glCreateProgram() { return _glCreateProgram(); }
    @Override public int glCreateShader(int type) { return _glCreateShader(type); }
    @Override public void glCullFace(int mode) { _glCullFace(mode); }

    @Override
    public void glDeleteBuffers(int n, IntBuffer buffers) { _glDeleteBuffers(n, buffers); }

    @Override
    public void glDeleteBuffer(int buffer) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, buffer);
        _glDeleteBuffers(1, buf);
    }

    @Override
    public void glDeleteFramebuffers(int n, IntBuffer framebuffers) { _glDeleteFramebuffers(n, framebuffers); }

    @Override
    public void glDeleteFramebuffer(int framebuffer) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, framebuffer);
        _glDeleteFramebuffers(1, buf);
    }

    @Override public void glDeleteProgram(int program) { _glDeleteProgram(program); }

    @Override
    public void glDeleteRenderbuffers(int n, IntBuffer renderbuffers) { _glDeleteRenderbuffers(n, renderbuffers); }

    @Override
    public void glDeleteRenderbuffer(int renderbuffer) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, renderbuffer);
        _glDeleteRenderbuffers(1, buf);
    }

    @Override public void glDeleteShader(int shader) { _glDeleteShader(shader); }

    @Override
    public void glDeleteTextures(int n, IntBuffer textures) { _glDeleteTextures(n, textures); }

    @Override
    public void glDeleteTexture(int texture) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, texture);
        _glDeleteTextures(1, buf);
    }

    @Override public void glDepthFunc(int func) { _glDepthFunc(func); }
    @Override public void glDepthMask(boolean flag) { _glDepthMask(flag); }
    @Override public void glDepthRangef(float zNear, float zFar) { _glDepthRangef(zNear, zFar); }
    @Override public void glDetachShader(int program, int shader) { _glDetachShader(program, shader); }
    @Override public void glDisable(int cap) { _glDisable(cap); }
    @Override public void glDisableVertexAttribArray(int index) { _glDisableVertexAttribArray(index); }
    @Override public void glDrawArrays(int mode, int first, int count) { _glDrawArrays(mode, first, count); }
    @Override public void glDrawElements(int mode, int count, int type, Buffer indices) { _glDrawElementsB(mode, count, type, indices); }
    @Override public void glDrawElements(int mode, int count, int type, int indices) { _glDrawElementsI(mode, count, type, Integer.toUnsignedLong(indices)); }
    @Override public void glEnable(int cap) { _glEnable(cap); }
    @Override public void glEnableVertexAttribArray(int index) { _glEnableVertexAttribArray(index); }
    @Override public void glFinish() { _glFinish(); }
    @Override public void glFlush() { _glFlush(); }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        _glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        _glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    @Override public void glFrontFace(int mode) { _glFrontFace(mode); }

    @Override
    public void glGenBuffers(int n, IntBuffer buffers) { _glGenBuffers(n, buffers); }

    @Override
    public int glGenBuffer() {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        _glGenBuffers(1, buf);
        return buf.get(0);
    }

    @Override public void glGenerateMipmap(int target) { _glGenerateMipmap(target); }

    @Override
    public void glGenFramebuffers(int n, IntBuffer framebuffers) { _glGenFramebuffers(n, framebuffers); }

    @Override
    public int glGenFramebuffer() {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        _glGenFramebuffers(1, buf);
        return buf.get(0);
    }

    @Override
    public void glGenRenderbuffers(int n, IntBuffer renderbuffers) { _glGenRenderbuffers(n, renderbuffers); }

    @Override
    public int glGenRenderbuffer() {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        _glGenRenderbuffers(1, buf);
        return buf.get(0);
    }

    @Override
    public void glGenTextures(int n, IntBuffer textures) { _glGenTextures(n, textures); }

    @Override
    public int glGenTexture() {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        _glGenTextures(1, buf);
        return buf.get(0);
    }

    @Override
    public String glGetActiveAttrib(int program, int index, IntBuffer size, IntBuffer type) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ByteBuffer nameBuf = ByteBuffer.allocateDirect(256);
        _glGetActiveAttrib(program, index, 256, lenBuf, size, type, nameBuf);
        int len = lenBuf.get(0);
        byte[] bytes = new byte[len];
        nameBuf.get(bytes);
        return new String(bytes);
    }

    @Override
    public String glGetActiveUniform(int program, int index, IntBuffer size, IntBuffer type) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ByteBuffer nameBuf = ByteBuffer.allocateDirect(256);
        _glGetActiveUniform(program, index, 256, lenBuf, size, type, nameBuf);
        int len = lenBuf.get(0);
        byte[] bytes = new byte[len];
        nameBuf.get(bytes);
        return new String(bytes);
    }

    @Override
    public void glGetAttachedShaders(int program, int maxcount, Buffer count, IntBuffer shaders) {
        _glGetAttachedShaders(program, maxcount, count, shaders);
    }

    @Override public int glGetAttribLocation(int program, String name) { return _glGetAttribLocation(program, name); }
    @Override public void glGetBooleanv(int pname, Buffer params) { _glGetBooleanv(pname, params); }
    @Override public void glGetBufferParameteriv(int target, int pname, IntBuffer params) { _glGetBufferParameteriv(target, pname, params); }
    @Override public int glGetError() { return _glGetError(); }
    @Override public void glGetFloatv(int pname, FloatBuffer params) { _glGetFloatv(pname, params); }

    @Override
    public void glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname, IntBuffer params) {
        _glGetFramebufferAttachmentParameteriv(target, attachment, pname, params);
    }

    @Override public void glGetIntegerv(int pname, IntBuffer params) { _glGetIntegerv(pname, params); }

    @Override
    public void glGetProgramiv(int program, int pname, IntBuffer params) { _glGetProgramiv(program, pname, params); }

    @Override
    public String glGetProgramInfoLog(int program) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        _glGetProgramiv(program, GL_INFO_LOG_LENGTH, lenBuf);
        int logLen = lenBuf.get(0);
        if (logLen <= 1) return "";
        ByteBuffer buf = ByteBuffer.allocateDirect(logLen);
        _glGetProgramInfoLog(program, logLen, null, buf);
        byte[] bytes = new byte[logLen - 1];
        buf.get(bytes);
        return new String(bytes);
    }

    @Override
    public void glGetRenderbufferParameteriv(int target, int pname, IntBuffer params) {
        _glGetRenderbufferParameteriv(target, pname, params);
    }

    @Override public void glGetShaderiv(int shader, int pname, IntBuffer params) { _glGetShaderiv(shader, pname, params); }

    @Override
    public String glGetShaderInfoLog(int shader) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        _glGetShaderiv(shader, GL_INFO_LOG_LENGTH, lenBuf);
        int logLen = lenBuf.get(0);
        if (logLen <= 1) return "";
        ByteBuffer buf = ByteBuffer.allocateDirect(logLen);
        _glGetShaderInfoLog(shader, logLen, null, buf);
        byte[] bytes = new byte[logLen - 1];
        buf.get(bytes);
        return new String(bytes);
    }

    @Override
    public void glGetShaderPrecisionFormat(int shadertype, int precisiontype, IntBuffer range, IntBuffer precision) {
        _glGetShaderPrecisionFormat(shadertype, precisiontype, range, precision);
    }

    public void glGetShaderSource(int shader, int bufsize, Buffer length, String source) {
        // query-only; not needed at runtime
    }

    @Override
    public String glGetString(int name) {
        long ptr = _glGetString(name);
        return ptr == 0 ? "" : VM.newStringUTF(ptr);
    }

    @Override public void glGetTexParameterfv(int target, int pname, FloatBuffer params) { _glGetTexParameterfv(target, pname, params); }
    @Override public void glGetTexParameteriv(int target, int pname, IntBuffer params) { _glGetTexParameteriv(target, pname, params); }
    @Override public void glGetUniformfv(int program, int location, FloatBuffer params) { _glGetUniformfv(program, location, params); }
    @Override public void glGetUniformiv(int program, int location, IntBuffer params) { _glGetUniformiv(program, location, params); }
    @Override public int glGetUniformLocation(int program, String name) { return _glGetUniformLocation(program, name); }
    @Override public void glGetVertexAttribfv(int index, int pname, FloatBuffer params) { _glGetVertexAttribfv(index, pname, params); }
    @Override public void glGetVertexAttribiv(int index, int pname, IntBuffer params) { _glGetVertexAttribiv(index, pname, params); }

    @Override
    public void glGetVertexAttribPointerv(int index, int pname, Buffer pointer) {
        // pointer-query; not needed at runtime
    }

    @Override public void glHint(int target, int mode) { _glHint(target, mode); }
    @Override public boolean glIsBuffer(int buffer) { return _glIsBuffer(buffer); }
    @Override public boolean glIsEnabled(int cap) { return _glIsEnabled(cap); }
    @Override public boolean glIsFramebuffer(int framebuffer) { return _glIsFramebuffer(framebuffer); }
    @Override public boolean glIsProgram(int program) { return _glIsProgram(program); }
    @Override public boolean glIsRenderbuffer(int renderbuffer) { return _glIsRenderbuffer(renderbuffer); }
    @Override public boolean glIsShader(int shader) { return _glIsShader(shader); }
    @Override public boolean glIsTexture(int texture) { return _glIsTexture(texture); }
    @Override public void glLineWidth(float width) { _glLineWidth(width); }
    @Override public void glLinkProgram(int program) { _glLinkProgram(program); }
    @Override public void glPixelStorei(int pname, int param) { _glPixelStorei(pname, param); }
    @Override public void glPolygonOffset(float factor, float units) { _glPolygonOffset(factor, units); }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, Buffer pixels) {
        _glReadPixels(x, y, width, height, format, type, pixels);
    }

    @Override public void glReleaseShaderCompiler() { _glReleaseShaderCompiler(); }
    @Override public void glRenderbufferStorage(int target, int internalformat, int width, int height) { _glRenderbufferStorage(target, internalformat, width, height); }
    @Override public void glSampleCoverage(float value, boolean invert) { _glSampleCoverage(value, invert); }
    @Override public void glScissor(int x, int y, int width, int height) { _glScissor(x, y, width, height); }

    @Override
    public void glShaderBinary(int n, IntBuffer shaders, int binaryformat, Buffer binary, int length) {
        // binary shaders not typically used with GLSL
    }

    @Override
    public void glShaderSource(int shader, String string) {
        // Build a const char** pointing to the UTF-8 bytes of the shader source.
        // VM.getStringUTFChars() returns a native long that is the address of the
        // null-terminated UTF-8 representation of the string.  We store that
        // pointer value into a direct ByteBuffer; when RoboVM marshals the buffer
        // for the @Bridge call it passes a void* to the buffer's data, which the
        // C function treats as const char** and dereferences to get the string.
        long strPtr = VM.getStringUTFChars(string);
        try {
            ByteBuffer ptrs = ByteBuffer.allocateDirect(Long.BYTES).order(ByteOrder.nativeOrder());
            ptrs.putLong(0, strPtr);
            _glShaderSource(shader, 1, ptrs, null);
        } finally {
            VM.free(strPtr);
        }
    }

    @Override public void glStencilFunc(int func, int ref, int mask) { _glStencilFunc(func, ref, mask); }
    @Override public void glStencilFuncSeparate(int face, int func, int ref, int mask) { _glStencilFuncSeparate(face, func, ref, mask); }
    @Override public void glStencilMask(int mask) { _glStencilMask(mask); }
    @Override public void glStencilMaskSeparate(int face, int mask) { _glStencilMaskSeparate(face, mask); }
    @Override public void glStencilOp(int fail, int zfail, int zpass) { _glStencilOp(fail, zfail, zpass); }
    @Override public void glStencilOpSeparate(int face, int fail, int zfail, int zpass) { _glStencilOpSeparate(face, fail, zfail, zpass); }

    static Buffer convert16bitBufferToRGBA8888(Buffer buffer, int type) {
        ByteBuffer byteBuffer = (ByteBuffer)buffer;
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer converted = ByteBuffer.allocateDirect(byteBuffer.limit() * 2);
        while (buffer.remaining() != 0) {
            short color = byteBuffer.getShort();
            int rgba8888;
            if (type == GL_UNSIGNED_SHORT_4_4_4_4) {
                byte r = (byte)((color >> 12) & 0x0F);
                byte g = (byte)((color >> 8) & 0x0F);
                byte b = (byte)((color >> 4) & 0x0F);
                byte a = (byte)((color) & 0x0F);
                rgba8888 = (r << 4 | r) << 24 | (g << 4 | g) << 16 | (b << 4 | b) << 8 | (a << 4 | a);
            } else if (type == GL_UNSIGNED_SHORT_5_6_5) {
                byte r = (byte)((color >> 11) & 0x1F);
                byte g = (byte)((color >> 5) & 0x3F);
                byte b = (byte)((color) & 0x1F);
                rgba8888 = (r << 3 | r >> 2) << 24 | (g << 2 | g >> 4) << 16 | (b << 3 | b >> 2) << 8 | 0xFF;
            } else {
                // GL_UNSIGNED_SHORT_5_5_5_1: bits 15-11=R, 10-6=G, 5-1=B, 0=A
                byte r = (byte)((color >> 11) & 0x1F);
                byte g = (byte)((color >> 6) & 0x1F);
                byte b = (byte)((color >> 1) & 0x1F);
                byte a = (byte)((color) & 0x1);
                rgba8888 = (r << 3 | r >> 2) << 24 | (g << 3 | g >> 2) << 16 | (b << 3 | b >> 2) << 8 | a * 255;
            }
            converted.putInt(rgba8888);
        }
        converted.position(0);
        return converted;
    }

    @Override
    public void glTexImage2D(int target, int level, int internalformat, int width, int height,
        int border, int format, int type, Buffer pixels) {
        if (!shouldConvert16bit) {
            _glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
            return;
        }
        if (type != GL_UNSIGNED_SHORT_5_6_5 && type != GL_UNSIGNED_SHORT_5_5_5_1 && type != GL_UNSIGNED_SHORT_4_4_4_4) {
            _glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
            return;
        }
        Buffer converted = convert16bitBufferToRGBA8888(pixels, type);
        _glTexImage2D(target, level, GL_RGBA, width, height, border, GL_RGBA, GL_UNSIGNED_BYTE, converted);
    }

    // Kept for compatibility with any code that calls glTexImage2DJNI directly
    public void glTexImage2DJNI(int target, int level, int internalformat, int width, int height,
        int border, int format, int type, Buffer pixels) {
        _glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    @Override public void glTexParameterf(int target, int pname, float param) { _glTexParameterf(target, pname, param); }
    @Override public void glTexParameterfv(int target, int pname, FloatBuffer params) { _glTexParameterfv(target, pname, params); }
    @Override public void glTexParameteri(int target, int pname, int param) { _glTexParameteri(target, pname, param); }
    @Override public void glTexParameteriv(int target, int pname, IntBuffer params) { _glTexParameteriv(target, pname, params); }

    @Override
    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
        int format, int type, Buffer pixels) {
        if (!shouldConvert16bit) {
            _glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
            return;
        }
        if (type != GL_UNSIGNED_SHORT_5_6_5 && type != GL_UNSIGNED_SHORT_5_5_5_1 && type != GL_UNSIGNED_SHORT_4_4_4_4) {
            _glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
            return;
        }
        Buffer converted = convert16bitBufferToRGBA8888(pixels, type);
        _glTexSubImage2D(target, level, xoffset, yoffset, width, height, GL_RGBA, GL_UNSIGNED_BYTE, converted);
    }

    // Kept for compatibility with any code that calls glTexSubImage2DJNI directly
    public void glTexSubImage2DJNI(int target, int level, int xoffset, int yoffset, int width, int height,
        int format, int type, Buffer pixels) {
        _glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    @Override public void glUniform1f(int location, float x) { _glUniform1f(location, x); }
    @Override public void glUniform1fv(int location, int count, FloatBuffer v) { _glUniform1fv(location, count, v); }

    @Override
    public void glUniform1fv(int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count);
        buf.rewind();
        _glUniform1fv(location, count, buf);
    }

    @Override public void glUniform1i(int location, int x) { _glUniform1i(location, x); }
    @Override public void glUniform1iv(int location, int count, IntBuffer v) { _glUniform1iv(location, count, v); }

    @Override
    public void glUniform1iv(int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count);
        buf.rewind();
        _glUniform1iv(location, count, buf);
    }

    @Override public void glUniform2f(int location, float x, float y) { _glUniform2f(location, x, y); }
    @Override public void glUniform2fv(int location, int count, FloatBuffer v) { _glUniform2fv(location, count, v); }

    @Override
    public void glUniform2fv(int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count * 2);
        buf.rewind();
        _glUniform2fv(location, count, buf);
    }

    @Override public void glUniform2i(int location, int x, int y) { _glUniform2i(location, x, y); }
    @Override public void glUniform2iv(int location, int count, IntBuffer v) { _glUniform2iv(location, count, v); }

    @Override
    public void glUniform2iv(int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count * 2);
        buf.rewind();
        _glUniform2iv(location, count, buf);
    }

    @Override public void glUniform3f(int location, float x, float y, float z) { _glUniform3f(location, x, y, z); }
    @Override public void glUniform3fv(int location, int count, FloatBuffer v) { _glUniform3fv(location, count, v); }

    @Override
    public void glUniform3fv(int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count * 3);
        buf.rewind();
        _glUniform3fv(location, count, buf);
    }

    @Override public void glUniform3i(int location, int x, int y, int z) { _glUniform3i(location, x, y, z); }
    @Override public void glUniform3iv(int location, int count, IntBuffer v) { _glUniform3iv(location, count, v); }

    @Override
    public void glUniform3iv(int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count * 3);
        buf.rewind();
        _glUniform3iv(location, count, buf);
    }

    @Override public void glUniform4f(int location, float x, float y, float z, float w) { _glUniform4f(location, x, y, z, w); }
    @Override public void glUniform4fv(int location, int count, FloatBuffer v) { _glUniform4fv(location, count, v); }

    @Override
    public void glUniform4fv(int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count * 4);
        buf.rewind();
        _glUniform4fv(location, count, buf);
    }

    @Override public void glUniform4i(int location, int x, int y, int z, int w) { _glUniform4i(location, x, y, z, w); }
    @Override public void glUniform4iv(int location, int count, IntBuffer v) { _glUniform4iv(location, count, v); }

    @Override
    public void glUniform4iv(int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count * 4);
        buf.rewind();
        _glUniform4iv(location, count, buf);
    }

    @Override public void glUniformMatrix2fv(int location, int count, boolean transpose, FloatBuffer value) { _glUniformMatrix2fv(location, count, transpose, value); }

    @Override
    public void glUniformMatrix2fv(int location, int count, boolean transpose, float[] value, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(value, offset, count * 4);
        buf.rewind();
        _glUniformMatrix2fv(location, count, transpose, buf);
    }

    @Override public void glUniformMatrix3fv(int location, int count, boolean transpose, FloatBuffer value) { _glUniformMatrix3fv(location, count, transpose, value); }

    @Override
    public void glUniformMatrix3fv(int location, int count, boolean transpose, float[] value, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 36).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(value, offset, count * 9);
        buf.rewind();
        _glUniformMatrix3fv(location, count, transpose, buf);
    }

    @Override public void glUniformMatrix4fv(int location, int count, boolean transpose, FloatBuffer value) { _glUniformMatrix4fv(location, count, transpose, value); }

    @Override
    public void glUniformMatrix4fv(int location, int count, boolean transpose, float[] value, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(value, offset, count * 16);
        buf.rewind();
        _glUniformMatrix4fv(location, count, transpose, buf);
    }

    @Override public void glUseProgram(int program) { _glUseProgram(program); }
    @Override public void glValidateProgram(int program) { _glValidateProgram(program); }
    @Override public void glVertexAttrib1f(int indx, float x) { _glVertexAttrib1f(indx, x); }
    @Override public void glVertexAttrib1fv(int indx, FloatBuffer values) { _glVertexAttrib1fv(indx, values); }
    @Override public void glVertexAttrib2f(int indx, float x, float y) { _glVertexAttrib2f(indx, x, y); }
    @Override public void glVertexAttrib2fv(int indx, FloatBuffer values) { _glVertexAttrib2fv(indx, values); }
    @Override public void glVertexAttrib3f(int indx, float x, float y, float z) { _glVertexAttrib3f(indx, x, y, z); }
    @Override public void glVertexAttrib3fv(int indx, FloatBuffer values) { _glVertexAttrib3fv(indx, values); }
    @Override public void glVertexAttrib4f(int indx, float x, float y, float z, float w) { _glVertexAttrib4f(indx, x, y, z, w); }
    @Override public void glVertexAttrib4fv(int indx, FloatBuffer values) { _glVertexAttrib4fv(indx, values); }

    @Override
    public void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, Buffer ptr) {
        _glVertexAttribPointerB(indx, size, type, normalized, stride, ptr);
    }

    @Override
    public void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, int ptr) {
        _glVertexAttribPointerI(indx, size, type, normalized, stride, Integer.toUnsignedLong(ptr));
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        IOSGLES20.x = x;
        IOSGLES20.y = y;
        IOSGLES20.width = width;
        IOSGLES20.height = height;
        _glViewport(x, y, width, height);
    }

    // Kept for compatibility with IOSGraphics which calls glViewportJni directly
    public void glViewportJni(int x, int y, int width, int height) {
        _glViewport(x, y, width, height);
    }
}
