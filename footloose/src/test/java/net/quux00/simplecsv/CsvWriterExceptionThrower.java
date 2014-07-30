package net.quux00.simplecsv;

import java.io.IOException;
import java.io.Writer;

import net.quux00.simplecsv.CsvWriter;

/**
 * Created by IntelliJ IDEA.
 * User: sconway
 * Date: 6/5/11
 * Time: 7:08 PM
 * To change this template use File | Settings | File Templates.
 */
public class CsvWriterExceptionThrower extends CsvWriter {
  public CsvWriterExceptionThrower(Writer writer) {
    super(writer);
  }

  @Override
  public void flush() throws IOException {
    throw new IOException("Exception thrown from Mock test flush method");
  }
}