package org.apache.nifi.processors.parquet.stream;

import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;

import java.io.OutputStream;

public class NifiParquetOutputFile implements OutputFile {

      private OutputStream outputStream;

      public NifiParquetOutputFile(OutputStream outputStream) {
        this.outputStream = outputStream;
      }

      @Override
      public PositionOutputStream create(long blockSizeHint) {
        return new NifiOutputStream(outputStream);
      }

      @Override
      public PositionOutputStream createOrOverwrite(long blockSizeHint) {
        return new NifiOutputStream(outputStream);
      }

      @Override
      public boolean supportsBlockSize() {
        return false;
      }

      @Override
      public long defaultBlockSize() {
        return 0;
      }

}
