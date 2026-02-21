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
 * functions directly via the already-linked OpenGLES framework, so no JNI
 * wrappers are needed at all.  It is compiled only for simulator builds because
 * robovm-simulator.xml points MobiVM at forge-gui-ios/src as an extra source
 * root, where it takes precedence over the same class in gdx-backend-robovm.jar.
 */

package com.badlogic.gdx.backends.iosrobovm;

import java.nio.*;

import com.badlogic.gdx.graphics.GL20;
import org.robovm.apple.foundation.NSProcessInfo;
import org.robovm.rt.VM;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Pointer;

@Library(Library.INTERNAL)
public class IOSGLES20 implements GL20 {

    final boolean shouldConvert16bit = IOSApplication.IS_METALANGLE
        && NSProcessInfo.getSharedProcessInfo().getEnvironment().containsKey("SIMULATOR_DEVICE_NAME");

    public IOSGLES20 () {
        init();
    }

    /** last viewport set, needed because GLKView resets the viewport on each call to render */
    public static int x, y, width, height;

    /** No-op: @Bridge methods do not need JNI initialisation. */
    private static void init () {}

    // -------------------------------------------------------------------------
    // GL20 interface — all backed by direct @Bridge calls into OpenGLES.framework
    // -------------------------------------------------------------------------

    @Bridge(symbol = "glActiveTexture")
    public native void glActiveTexture (int texture);

    @Bridge(symbol = "glAttachShader")
    public native void glAttachShader (int program, int shader);

    @Bridge(symbol = "glBindAttribLocation")
    public native void glBindAttribLocation (int program, int index, String name);

    @Bridge(symbol = "glBindBuffer")
    public native void glBindBuffer (int target, int buffer);

    @Bridge(symbol = "glBindFramebuffer")
    public native void glBindFramebuffer (int target, int framebuffer);

    @Bridge(symbol = "glBindRenderbuffer")
    public native void glBindRenderbuffer (int target, int renderbuffer);

    @Bridge(symbol = "glBindTexture")
    public native void glBindTexture (int target, int texture);

    @Bridge(symbol = "glBlendColor")
    public native void glBlendColor (float red, float green, float blue, float alpha);

    @Bridge(symbol = "glBlendEquation")
    public native void glBlendEquation (int mode);

    @Bridge(symbol = "glBlendEquationSeparate")
    public native void glBlendEquationSeparate (int modeRGB, int modeAlpha);

    @Bridge(symbol = "glBlendFunc")
    public native void glBlendFunc (int sfactor, int dfactor);

    @Bridge(symbol = "glBlendFuncSeparate")
    public native void glBlendFuncSeparate (int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);

    @Bridge(symbol = "glBufferData")
    public native void glBufferData (int target, int size, Buffer data, int usage);

    @Bridge(symbol = "glBufferSubData")
    public native void glBufferSubData (int target, int offset, int size, Buffer data);

    @Bridge(symbol = "glCheckFramebufferStatus")
    public native int glCheckFramebufferStatus (int target);

    @Bridge(symbol = "glClear")
    public native void glClear (int mask);

    @Bridge(symbol = "glClearColor")
    public native void glClearColor (float red, float green, float blue, float alpha);

    @Bridge(symbol = "glClearDepthf")
    public native void glClearDepthf (float depth);

    @Bridge(symbol = "glClearStencil")
    public native void glClearStencil (int s);

    @Bridge(symbol = "glColorMask")
    public native void glColorMask (boolean red, boolean green, boolean blue, boolean alpha);

    @Bridge(symbol = "glCompileShader")
    public native void glCompileShader (int shader);

    @Bridge(symbol = "glCompressedTexImage2D")
    public native void glCompressedTexImage2D (int target, int level, int internalformat, int width, int height,
        int border, int imageSize, Buffer data);

    @Bridge(symbol = "glCompressedTexSubImage2D")
    public native void glCompressedTexSubImage2D (int target, int level, int xoffset, int yoffset, int width,
        int height, int format, int imageSize, Buffer data);

    @Bridge(symbol = "glCopyTexImage2D")
    public native void glCopyTexImage2D (int target, int level, int internalformat, int x, int y, int width,
        int height, int border);

    @Bridge(symbol = "glCopyTexSubImage2D")
    public native void glCopyTexSubImage2D (int target, int level, int xoffset, int yoffset, int x, int y,
        int width, int height);

    @Bridge(symbol = "glCreateProgram")
    public native int glCreateProgram ();

    @Bridge(symbol = "glCreateShader")
    public native int glCreateShader (int type);

    @Bridge(symbol = "glCullFace")
    public native void glCullFace (int mode);

    @Bridge(symbol = "glDeleteBuffers")
    public native void glDeleteBuffers (int n, IntBuffer buffers);

    public void glDeleteBuffer (int buffer) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, buffer);
        glDeleteBuffers(1, buf);
    }

    @Bridge(symbol = "glDeleteFramebuffers")
    public native void glDeleteFramebuffers (int n, IntBuffer framebuffers);

    public void glDeleteFramebuffer (int framebuffer) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, framebuffer);
        glDeleteFramebuffers(1, buf);
    }

    @Bridge(symbol = "glDeleteProgram")
    public native void glDeleteProgram (int program);

    @Bridge(symbol = "glDeleteRenderbuffers")
    public native void glDeleteRenderbuffers (int n, IntBuffer renderbuffers);

    public void glDeleteRenderbuffer (int renderbuffer) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, renderbuffer);
        glDeleteRenderbuffers(1, buf);
    }

    @Bridge(symbol = "glDeleteShader")
    public native void glDeleteShader (int shader);

    @Bridge(symbol = "glDeleteTextures")
    public native void glDeleteTextures (int n, IntBuffer textures);

    public void glDeleteTexture (int texture) {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(0, texture);
        glDeleteTextures(1, buf);
    }

    @Bridge(symbol = "glDepthFunc")
    public native void glDepthFunc (int func);

    @Bridge(symbol = "glDepthMask")
    public native void glDepthMask (boolean flag);

    @Bridge(symbol = "glDepthRangef")
    public native void glDepthRangef (float zNear, float zFar);

    @Bridge(symbol = "glDetachShader")
    public native void glDetachShader (int program, int shader);

    @Bridge(symbol = "glDisable")
    public native void glDisable (int cap);

    @Bridge(symbol = "glDisableVertexAttribArray")
    public native void glDisableVertexAttribArray (int index);

    @Bridge(symbol = "glDrawArrays")
    public native void glDrawArrays (int mode, int first, int count);

    @Bridge(symbol = "glDrawElements")
    public native void glDrawElements (int mode, int count, int type, Buffer indices);

    @Bridge(symbol = "glDrawElements")
    public native void glDrawElements (int mode, int count, int type, int indices);

    @Bridge(symbol = "glEnable")
    public native void glEnable (int cap);

    @Bridge(symbol = "glEnableVertexAttribArray")
    public native void glEnableVertexAttribArray (int index);

    @Bridge(symbol = "glFinish")
    public native void glFinish ();

    @Bridge(symbol = "glFlush")
    public native void glFlush ();

    @Bridge(symbol = "glFramebufferRenderbuffer")
    public native void glFramebufferRenderbuffer (int target, int attachment, int renderbuffertarget,
        int renderbuffer);

    @Bridge(symbol = "glFramebufferTexture2D")
    public native void glFramebufferTexture2D (int target, int attachment, int textarget, int texture, int level);

    @Bridge(symbol = "glFrontFace")
    public native void glFrontFace (int mode);

    @Bridge(symbol = "glGenBuffers")
    public native void glGenBuffers (int n, IntBuffer buffers);

    public int glGenBuffer () {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        glGenBuffers(1, buf);
        return buf.get(0);
    }

    @Bridge(symbol = "glGenerateMipmap")
    public native void glGenerateMipmap (int target);

    @Bridge(symbol = "glGenFramebuffers")
    public native void glGenFramebuffers (int n, IntBuffer framebuffers);

    public int glGenFramebuffer () {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        glGenFramebuffers(1, buf);
        return buf.get(0);
    }

    @Bridge(symbol = "glGenRenderbuffers")
    public native void glGenRenderbuffers (int n, IntBuffer renderbuffers);

    public int glGenRenderbuffer () {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        glGenRenderbuffers(1, buf);
        return buf.get(0);
    }

    @Bridge(symbol = "glGenTextures")
    public native void glGenTextures (int n, IntBuffer textures);

    public int glGenTexture () {
        IntBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        glGenTextures(1, buf);
        return buf.get(0);
    }

    public String glGetActiveAttrib (int program, int index, IntBuffer size, IntBuffer type) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ByteBuffer nameBuf = ByteBuffer.allocateDirect(256);
        glGetActiveAttrib0(program, index, 256, lenBuf, size, type, nameBuf);
        int len = lenBuf.get(0);
        byte[] bytes = new byte[len];
        nameBuf.get(bytes);
        return new String(bytes);
    }

    @Bridge(symbol = "glGetActiveAttrib")
    private static native void glGetActiveAttrib0 (int program, int index, int bufSize, IntBuffer length,
        IntBuffer size, IntBuffer type, ByteBuffer name);

    public String glGetActiveUniform (int program, int index, IntBuffer size, IntBuffer type) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ByteBuffer nameBuf = ByteBuffer.allocateDirect(256);
        glGetActiveUniform0(program, index, 256, lenBuf, size, type, nameBuf);
        int len = lenBuf.get(0);
        byte[] bytes = new byte[len];
        nameBuf.get(bytes);
        return new String(bytes);
    }

    @Bridge(symbol = "glGetActiveUniform")
    private static native void glGetActiveUniform0 (int program, int index, int bufSize, IntBuffer length,
        IntBuffer size, IntBuffer type, ByteBuffer name);

    public void glGetAttachedShaders (int program, int maxcount, Buffer count, IntBuffer shaders) {
        glGetAttachedShaders0(program, maxcount, count, shaders);
    }

    @Bridge(symbol = "glGetAttachedShaders")
    private static native void glGetAttachedShaders0 (int program, int maxcount, Buffer count, IntBuffer shaders);

    @Bridge(symbol = "glGetAttribLocation")
    public native int glGetAttribLocation (int program, String name);

    @Bridge(symbol = "glGetBooleanv")
    public native void glGetBooleanv (int pname, Buffer params);

    @Bridge(symbol = "glGetBufferParameteriv")
    public native void glGetBufferParameteriv (int target, int pname, IntBuffer params);

    @Bridge(symbol = "glGetError")
    public native int glGetError ();

    @Bridge(symbol = "glGetFloatv")
    public native void glGetFloatv (int pname, FloatBuffer params);

    @Bridge(symbol = "glGetFramebufferAttachmentParameteriv")
    public native void glGetFramebufferAttachmentParameteriv (int target, int attachment, int pname,
        IntBuffer params);

    @Bridge(symbol = "glGetIntegerv")
    public native void glGetIntegerv (int pname, IntBuffer params);

    @Bridge(symbol = "glGetProgramiv")
    public native void glGetProgramiv (int program, int pname, IntBuffer params);

    public String glGetProgramInfoLog (int program) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, lenBuf);
        int logLen = lenBuf.get(0);
        if (logLen <= 1) return "";
        ByteBuffer buf = ByteBuffer.allocateDirect(logLen);
        glGetProgramInfoLog0(program, logLen, null, buf);
        byte[] bytes = new byte[logLen - 1];
        buf.get(bytes);
        return new String(bytes);
    }

    @Bridge(symbol = "glGetProgramInfoLog")
    private static native void glGetProgramInfoLog0 (int program, int bufSize, IntBuffer length, ByteBuffer infoLog);

    @Bridge(symbol = "glGetRenderbufferParameteriv")
    public native void glGetRenderbufferParameteriv (int target, int pname, IntBuffer params);

    @Bridge(symbol = "glGetShaderiv")
    public native void glGetShaderiv (int shader, int pname, IntBuffer params);

    public String glGetShaderInfoLog (int shader) {
        IntBuffer lenBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, lenBuf);
        int logLen = lenBuf.get(0);
        if (logLen <= 1) return "";
        ByteBuffer buf = ByteBuffer.allocateDirect(logLen);
        glGetShaderInfoLog0(shader, logLen, null, buf);
        byte[] bytes = new byte[logLen - 1];
        buf.get(bytes);
        return new String(bytes);
    }

    @Bridge(symbol = "glGetShaderInfoLog")
    private static native void glGetShaderInfoLog0 (int shader, int bufSize, IntBuffer length, ByteBuffer infoLog);

    @Bridge(symbol = "glGetShaderPrecisionFormat")
    public native void glGetShaderPrecisionFormat (int shadertype, int precisiontype, IntBuffer range,
        IntBuffer precision);

    public void glGetShaderSource (int shader, int bufsize, Buffer length, String source) {
        // query-only; rarely used at runtime
    }

    @Bridge(symbol = "glGetString")
    private static native @Pointer long glGetStringPtr (int name);

    public String glGetString (int name) {
        long ptr = glGetStringPtr(name);
        return ptr == 0 ? "" : VM.newStringUTF(ptr);
    }

    @Bridge(symbol = "glGetTexParameterfv")
    public native void glGetTexParameterfv (int target, int pname, FloatBuffer params);

    @Bridge(symbol = "glGetTexParameteriv")
    public native void glGetTexParameteriv (int target, int pname, IntBuffer params);

    @Bridge(symbol = "glGetUniformfv")
    public native void glGetUniformfv (int program, int location, FloatBuffer params);

    @Bridge(symbol = "glGetUniformiv")
    public native void glGetUniformiv (int program, int location, IntBuffer params);

    @Bridge(symbol = "glGetUniformLocation")
    public native int glGetUniformLocation (int program, String name);

    @Bridge(symbol = "glGetVertexAttribfv")
    public native void glGetVertexAttribfv (int index, int pname, FloatBuffer params);

    @Bridge(symbol = "glGetVertexAttribiv")
    public native void glGetVertexAttribiv (int index, int pname, IntBuffer params);

    public void glGetVertexAttribPointerv (int index, int pname, Buffer pointer) {
        // pointer-query; rarely needed at runtime
    }

    @Bridge(symbol = "glHint")
    public native void glHint (int target, int mode);

    @Bridge(symbol = "glIsBuffer")
    public native boolean glIsBuffer (int buffer);

    @Bridge(symbol = "glIsEnabled")
    public native boolean glIsEnabled (int cap);

    @Bridge(symbol = "glIsFramebuffer")
    public native boolean glIsFramebuffer (int framebuffer);

    @Bridge(symbol = "glIsProgram")
    public native boolean glIsProgram (int program);

    @Bridge(symbol = "glIsRenderbuffer")
    public native boolean glIsRenderbuffer (int renderbuffer);

    @Bridge(symbol = "glIsShader")
    public native boolean glIsShader (int shader);

    @Bridge(symbol = "glIsTexture")
    public native boolean glIsTexture (int texture);

    @Bridge(symbol = "glLineWidth")
    public native void glLineWidth (float width);

    @Bridge(symbol = "glLinkProgram")
    public native void glLinkProgram (int program);

    @Bridge(symbol = "glPixelStorei")
    public native void glPixelStorei (int pname, int param);

    @Bridge(symbol = "glPolygonOffset")
    public native void glPolygonOffset (float factor, float units);

    @Bridge(symbol = "glReadPixels")
    public native void glReadPixels (int x, int y, int width, int height, int format, int type, Buffer pixels);

    @Bridge(symbol = "glReleaseShaderCompiler")
    public native void glReleaseShaderCompiler ();

    @Bridge(symbol = "glRenderbufferStorage")
    public native void glRenderbufferStorage (int target, int internalformat, int width, int height);

    @Bridge(symbol = "glSampleCoverage")
    public native void glSampleCoverage (float value, boolean invert);

    @Bridge(symbol = "glScissor")
    public native void glScissor (int x, int y, int width, int height);

    public void glShaderBinary (int n, IntBuffer shaders, int binaryformat, Buffer binary, int length) {
        // binary shaders not typically used with GLSL
    }

    @Bridge(symbol = "glShaderSource")
    public native void glShaderSource (int shader, int count, String[] string, IntBuffer length);

    public void glShaderSource (int shader, String string) {
        glShaderSource(shader, 1, new String[] {string}, null);
    }

    @Bridge(symbol = "glStencilFunc")
    public native void glStencilFunc (int func, int ref, int mask);

    @Bridge(symbol = "glStencilFuncSeparate")
    public native void glStencilFuncSeparate (int face, int func, int ref, int mask);

    @Bridge(symbol = "glStencilMask")
    public native void glStencilMask (int mask);

    @Bridge(symbol = "glStencilMaskSeparate")
    public native void glStencilMaskSeparate (int face, int mask);

    @Bridge(symbol = "glStencilOp")
    public native void glStencilOp (int fail, int zfail, int zpass);

    @Bridge(symbol = "glStencilOpSeparate")
    public native void glStencilOpSeparate (int face, int fail, int zfail, int zpass);

    static Buffer convert16bitBufferToRGBA8888 (Buffer buffer, int type) {
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

    public void glTexImage2D (int target, int level, int internalformat, int width, int height, int border,
        int format, int type, Buffer pixels) {
        if (!shouldConvert16bit) {
            glTexImage2DJNI(target, level, internalformat, width, height, border, format, type, pixels);
            return;
        }
        if (type != GL_UNSIGNED_SHORT_5_6_5 && type != GL_UNSIGNED_SHORT_5_5_5_1 && type != GL_UNSIGNED_SHORT_4_4_4_4) {
            glTexImage2DJNI(target, level, internalformat, width, height, border, format, type, pixels);
            return;
        }
        Buffer converted = convert16bitBufferToRGBA8888(pixels, type);
        glTexImage2DJNI(target, level, GL_RGBA, width, height, border, GL_RGBA, GL_UNSIGNED_BYTE, converted);
    }

    @Bridge(symbol = "glTexImage2D")
    public native void glTexImage2DJNI (int target, int level, int internalformat, int width, int height,
        int border, int format, int type, Buffer pixels);

    @Bridge(symbol = "glTexParameterf")
    public native void glTexParameterf (int target, int pname, float param);

    @Bridge(symbol = "glTexParameterfv")
    public native void glTexParameterfv (int target, int pname, FloatBuffer params);

    @Bridge(symbol = "glTexParameteri")
    public native void glTexParameteri (int target, int pname, int param);

    @Bridge(symbol = "glTexParameteriv")
    public native void glTexParameteriv (int target, int pname, IntBuffer params);

    public void glTexSubImage2D (int target, int level, int xoffset, int yoffset, int width, int height,
        int format, int type, Buffer pixels) {
        if (!shouldConvert16bit) {
            glTexSubImage2DJNI(target, level, xoffset, yoffset, width, height, format, type, pixels);
            return;
        }
        if (type != GL_UNSIGNED_SHORT_5_6_5 && type != GL_UNSIGNED_SHORT_5_5_5_1 && type != GL_UNSIGNED_SHORT_4_4_4_4) {
            glTexSubImage2DJNI(target, level, xoffset, yoffset, width, height, format, type, pixels);
            return;
        }
        Buffer converted = convert16bitBufferToRGBA8888(pixels, type);
        glTexSubImage2DJNI(target, level, xoffset, yoffset, width, height, GL_RGBA, GL_UNSIGNED_BYTE, converted);
    }

    @Bridge(symbol = "glTexSubImage2D")
    public native void glTexSubImage2DJNI (int target, int level, int xoffset, int yoffset, int width,
        int height, int format, int type, Buffer pixels);

    @Bridge(symbol = "glUniform1f")
    public native void glUniform1f (int location, float x);

    @Bridge(symbol = "glUniform1fv")
    public native void glUniform1fv (int location, int count, FloatBuffer v);

    public void glUniform1fv (int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count);
        buf.rewind();
        glUniform1fv(location, count, buf);
    }

    @Bridge(symbol = "glUniform1i")
    public native void glUniform1i (int location, int x);

    @Bridge(symbol = "glUniform1iv")
    public native void glUniform1iv (int location, int count, IntBuffer v);

    public void glUniform1iv (int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count);
        buf.rewind();
        glUniform1iv(location, count, buf);
    }

    @Bridge(symbol = "glUniform2f")
    public native void glUniform2f (int location, float x, float y);

    @Bridge(symbol = "glUniform2fv")
    public native void glUniform2fv (int location, int count, FloatBuffer v);

    public void glUniform2fv (int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count * 2);
        buf.rewind();
        glUniform2fv(location, count, buf);
    }

    @Bridge(symbol = "glUniform2i")
    public native void glUniform2i (int location, int x, int y);

    @Bridge(symbol = "glUniform2iv")
    public native void glUniform2iv (int location, int count, IntBuffer v);

    public void glUniform2iv (int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count * 2);
        buf.rewind();
        glUniform2iv(location, count, buf);
    }

    @Bridge(symbol = "glUniform3f")
    public native void glUniform3f (int location, float x, float y, float z);

    @Bridge(symbol = "glUniform3fv")
    public native void glUniform3fv (int location, int count, FloatBuffer v);

    public void glUniform3fv (int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count * 3);
        buf.rewind();
        glUniform3fv(location, count, buf);
    }

    @Bridge(symbol = "glUniform3i")
    public native void glUniform3i (int location, int x, int y, int z);

    @Bridge(symbol = "glUniform3iv")
    public native void glUniform3iv (int location, int count, IntBuffer v);

    public void glUniform3iv (int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count * 3);
        buf.rewind();
        glUniform3iv(location, count, buf);
    }

    @Bridge(symbol = "glUniform4f")
    public native void glUniform4f (int location, float x, float y, float z, float w);

    @Bridge(symbol = "glUniform4fv")
    public native void glUniform4fv (int location, int count, FloatBuffer v);

    public void glUniform4fv (int location, int count, float[] v, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v, offset, count * 4);
        buf.rewind();
        glUniform4fv(location, count, buf);
    }

    @Bridge(symbol = "glUniform4i")
    public native void glUniform4i (int location, int x, int y, int z, int w);

    @Bridge(symbol = "glUniform4iv")
    public native void glUniform4iv (int location, int count, IntBuffer v);

    public void glUniform4iv (int location, int count, int[] v, int offset) {
        IntBuffer buf = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder()).asIntBuffer();
        buf.put(v, offset, count * 4);
        buf.rewind();
        glUniform4iv(location, count, buf);
    }

    @Bridge(symbol = "glUniformMatrix2fv")
    public native void glUniformMatrix2fv (int location, int count, boolean transpose, FloatBuffer value);

    public void glUniformMatrix2fv (int location, int count, boolean transpose, float[] value, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(value, offset, count * 4);
        buf.rewind();
        glUniformMatrix2fv(location, count, transpose, buf);
    }

    @Bridge(symbol = "glUniformMatrix3fv")
    public native void glUniformMatrix3fv (int location, int count, boolean transpose, FloatBuffer value);

    public void glUniformMatrix3fv (int location, int count, boolean transpose, float[] value, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 36).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(value, offset, count * 9);
        buf.rewind();
        glUniformMatrix3fv(location, count, transpose, buf);
    }

    @Bridge(symbol = "glUniformMatrix4fv")
    public native void glUniformMatrix4fv (int location, int count, boolean transpose, FloatBuffer value);

    public void glUniformMatrix4fv (int location, int count, boolean transpose, float[] value, int offset) {
        FloatBuffer buf = ByteBuffer.allocateDirect(count * 64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(value, offset, count * 16);
        buf.rewind();
        glUniformMatrix4fv(location, count, transpose, buf);
    }

    @Bridge(symbol = "glUseProgram")
    public native void glUseProgram (int program);

    @Bridge(symbol = "glValidateProgram")
    public native void glValidateProgram (int program);

    @Bridge(symbol = "glVertexAttrib1f")
    public native void glVertexAttrib1f (int indx, float x);

    @Bridge(symbol = "glVertexAttrib1fv")
    public native void glVertexAttrib1fv (int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttrib2f")
    public native void glVertexAttrib2f (int indx, float x, float y);

    @Bridge(symbol = "glVertexAttrib2fv")
    public native void glVertexAttrib2fv (int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttrib3f")
    public native void glVertexAttrib3f (int indx, float x, float y, float z);

    @Bridge(symbol = "glVertexAttrib3fv")
    public native void glVertexAttrib3fv (int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttrib4f")
    public native void glVertexAttrib4f (int indx, float x, float y, float z, float w);

    @Bridge(symbol = "glVertexAttrib4fv")
    public native void glVertexAttrib4fv (int indx, FloatBuffer values);

    @Bridge(symbol = "glVertexAttribPointer")
    public native void glVertexAttribPointer (int indx, int size, int type, boolean normalized, int stride,
        Buffer ptr);

    @Bridge(symbol = "glVertexAttribPointer")
    public native void glVertexAttribPointer (int indx, int size, int type, boolean normalized, int stride,
        int ptr);

    public void glViewport (int x, int y, int width, int height) {
        IOSGLES20.x = x;
        IOSGLES20.y = y;
        IOSGLES20.width = width;
        IOSGLES20.height = height;
        glViewportJni(x, y, width, height);
    }

    @Bridge(symbol = "glViewport")
    public native void glViewportJni (int x, int y, int width, int height);
}
