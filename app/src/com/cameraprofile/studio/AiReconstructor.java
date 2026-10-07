package com.cameraprofile.studio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import org.tensorflow.lite.*;

/** Bundled ESRGAN model using the offline LiteRT CPU interpreter. */
public final class AiReconstructor implements AutoCloseable, NeuralTiles.Model {
  public static final String MODEL_SHA =
      "1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b";
  private final Interpreter interpreter;
  private final int size, scale;
  private final ByteBuffer inputBuffer, outputBuffer;

  public AiReconstructor(Context context) throws Exception {
    ByteBuffer model;
    try (AssetFileDescriptor descriptor = context.getAssets().openFd("ESRGAN.tflite");
        FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
      model =
          stream
              .getChannel()
              .map(
                  FileChannel.MapMode.READ_ONLY,
                  descriptor.getStartOffset(),
                  descriptor.getDeclaredLength());
    }
    // Verify the bundled weights before allocating interpreter tensors.
    byte[] bytes = new byte[model.remaining()];
    model.duplicate().get(bytes);
    if (!MODEL_SHA.equals(JpegEngine.hash(bytes))) throw new IOException("AI 模型完整性校验失败");
    Interpreter.Options options =
        new Interpreter.Options()
            .setNumThreads(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())))
            .setUseXNNPACK(true);
    Interpreter candidate = null;
    try {
      candidate = new Interpreter(model, options);
      candidate.allocateTensors();
      int[] in = candidate.getInputTensor(0).shape(), out = candidate.getOutputTensor(0).shape();
      if (in.length != 4
          || out.length != 4
          || in[0] != 1
          || out[0] != 1
          || in[1] != in[2]
          || in[3] != 3
          || out[3] != 3
          || out[1] != out[2]
          || out[1] % in[1] != 0
          || in[1] < 17
          || candidate.getInputTensor(0).dataType() != DataType.FLOAT32
          || candidate.getOutputTensor(0).dataType() != DataType.FLOAT32)
        throw new IOException("AI 模型输入输出形状不兼容");
      size = in[1];
      scale = out[1] / in[1];
      interpreter = candidate;
      inputBuffer =
          ByteBuffer.allocateDirect(candidate.getInputTensor(0).numBytes())
              .order(ByteOrder.nativeOrder());
      outputBuffer =
          ByteBuffer.allocateDirect(candidate.getOutputTensor(0).numBytes())
              .order(ByteOrder.nativeOrder());
    } catch (Throwable error) {
      if (candidate != null) candidate.close();
      if (error instanceof OutOfMemoryError) throw (OutOfMemoryError) error;
      throw new IOException("手机 AI 运行库不兼容，请关闭 AI 后使用像素重建", error);
    }
  }

  public int inputSize() {
    return size;
  }

  public int scale() {
    return scale;
  }

  public void infer(float[] input, float[] output) throws Exception {
    inputBuffer.rewind();
    inputBuffer.asFloatBuffer().put(input);
    outputBuffer.rewind();
    interpreter.run(inputBuffer, outputBuffer);
    outputBuffer.rewind();
    outputBuffer.asFloatBuffer().get(output);
  }

  public void close() {
    interpreter.close();
  }
}
