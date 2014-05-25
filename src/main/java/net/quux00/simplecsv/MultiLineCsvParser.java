package net.quux00.simplecsv;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The heart of the simplecsv library is the parser.
 * If you want to construct anything except the default parser, it is recommended
 * that you use the CsvParserBuilder.
 * 
 * The parser can be used standalone without the Reader. For example in a Hadoop
 * MapReduce scenario, no reader is needed, just a parser, so almost all of the
 * core logic of the library is in the CsvParser, not other classes.
 *
 * Options / configurations:
 * - change the separator/delimiter char
 * - change the quote char, including specifying no quote char by setting it to ParserUtil.NULL_CHARACTER
 * - change the escape char, including specifying no escape by setting it to ParserUtil.NULL_CHARACTER
 * - turn on strictQuotes mode
 * - turn on trimWhitespace mode
 * - turn on allowUnbalancedQuotes mode
 * - turn off retainEscapeChars mode
 * - turn on alwaysQuoteOutput mode
 * - turn on alwaysAllowDoubleEscapedQuotes
 *
 * This parser is ThreadSafe - Use the same CsvParser in as many threads as you want.
 */
public class MultiLineCsvParser implements CsvParser {

  final char separator;
  final char quotechar;
  final char escapechar;
  final boolean strictQuotes;             // if true, characters outside the quotes are ignored
  final boolean trimWhiteSpace;           // if true, trim leading/trailing white space from tokens
  final boolean allowedUnbalancedQuotes;  // if true, allows unbalanced quotes in a token
  final boolean retainOuterQuotes;        // if true, outer quote chars are retained
  final boolean retainEscapeChars;        // if true, leaves escape chars in; if false removes them
  final boolean alwaysQuoteOutput;        // if true, put quote around around all outgoing tokens
  final boolean allowsDoubledEscapedQuotes; // if true, allows quotes to exist within a quoted field as long as they are doubled.

  static final int INITIAL_READ_SIZE = 128;

  private static final boolean debug = false;

//  final StringBuilder sb = new StringBuilder(INITIAL_READ_SIZE);
  final State state = new State();
  
  public MultiLineCsvParser() {
    separator = ParserUtil.DEFAULT_SEPARATOR;
    quotechar = ParserUtil.DEFAULT_QUOTE_CHAR;
    escapechar = ParserUtil.DEFAULT_ESCAPE_CHAR;
    strictQuotes = ParserUtil.DEFAULT_STRICT_QUOTES;
    trimWhiteSpace = ParserUtil.DEFAULT_TRIM_WS;
    allowedUnbalancedQuotes = ParserUtil.DEFAULT_ALLOW_UNBALANCED_QUOTES;
    retainOuterQuotes = ParserUtil.DEFAULT_RETAIN_OUTER_QUOTES;
    retainEscapeChars = ParserUtil.DEFAULT_RETAIN_ESCAPE_CHARS;
    alwaysQuoteOutput = ParserUtil.DEFAULT_ALWAYS_QUOTE_OUTPUT;
    allowsDoubledEscapedQuotes = ParserUtil.DEFAULT_ALLOW_DOUBLED_ESCAPED_QUOTES;
  }

  /**
   * Constructor with all options settable. Unless you want the default
   * behavior, use the Builder to set the options you want.
   *
   * @param separator single char that separates values in the list
   * @param quotechar single char that is used to quote values
   * @param escapechar single char that is used to escape values
   * @param strictQuotes only accept values if they are between quote characters
   * @param trimWhiteSpace trims leading and trailing whitespace of each token
   * before it is returned
   * @param allowedUnbalancedQuotes
   * @param retainOuterQuotes
   * @param retainEscapeChars
   * @param alwaysQuoteOutput
   * @param allowsDoubledEscapedQuotes
   */
  public MultiLineCsvParser(final char separator, final char quotechar, final char escapechar,
      final boolean strictQuotes, final boolean trimWhiteSpace, final boolean allowedUnbalancedQuotes,
      final boolean retainOuterQuotes, final boolean retainEscapeChars,
      final boolean alwaysQuoteOutput, final boolean allowsDoubledEscapedQuotes) {
    this.separator = separator;
    this.quotechar = quotechar;
    this.escapechar = escapechar;
    this.strictQuotes = strictQuotes;
    this.trimWhiteSpace = trimWhiteSpace;
    this.allowedUnbalancedQuotes = allowedUnbalancedQuotes;
    this.retainOuterQuotes = retainOuterQuotes;
    this.retainEscapeChars = retainEscapeChars;
    this.alwaysQuoteOutput = alwaysQuoteOutput;
    this.allowsDoubledEscapedQuotes = allowsDoubledEscapedQuotes;

    checkInvariants();
  }

  private void checkInvariants() {
    if (ParserUtil.anyCharactersAreTheSame(separator, quotechar, escapechar)) {
      throw new UnsupportedOperationException("The separator, quote, and escape characters must be different!");
    }
    if (separator == ParserUtil.NULL_CHARACTER) {
      throw new UnsupportedOperationException("The separator character must be defined!");
    }
    if (quotechar == ParserUtil.NULL_CHARACTER && alwaysQuoteOutput) {
      throw new UnsupportedOperationException("The quote character must be defined to set alwaysQuoteOutput=true!");
    }
  }

  // keep track of mutable States for FSM of parsing
  static final class State {

    boolean inQuotes = false;
    boolean inEscape = false;

    public void quoteFound() {
      if (!inEscape) {
        inQuotes = !inQuotes;
      }
    }

    public void escapeFound(boolean escFound) {
      if (escFound) {
        inEscape = !inEscape;
      } else {
        inEscape = false;
      }
    }

    public void reset() {
      inQuotes = inEscape = false;
    }
  }

  @Override
  public List<String> parse(String s) {
    if (s == null) {
      return null;
    }
    try {
      return parseNext(new StringReader(s));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
  
  /**
   * Parses a record (a single row of fields) as defined by the presence of LF
   * or CRLF. If a CR or CRLF is detected inside a quoted value then the value
   * returned will contain them and the parser will continue to look for the
   * real record ending. 
   *
   * @param reader the Reader get our data from
   * @return parsed tokens as List of Strings
   * @throws java.io.IOException
   */
  public List<String> parseNext(Reader reader) throws IOException {

    // check eof first
    int r = reader.read();
    if (r == -1) {
      return null;
    }

    state.reset();
    final StringBuilder sb = new StringBuilder(INITIAL_READ_SIZE);
//    final State state = new State();
    final List<String> toks = new ArrayList<String>();

    decide:
      while (r != -1) {

        if (debug) {
          // Debug is private static final field
          // Once set to false, this code will not be emitted in compiled class.
          System.out.println("Char(" + (char) r + ") to int(" + r + ")");
        }

        if (isQuoteChar(r)) {
          if (allowsDoubledEscapedQuotes && state.inQuotes) {
            if (isQuoteChar(r = reader.read())) {
              // then consume and follow usual flow
              sb.append((char) r);
            } else {
              // HANDLE QUOTE AND (a) BREAK IF EOF
              // OR (b) START AT TOP WITH OBTAINED NEXT CHAR
              handleQuote(sb);
              continue decide;
            }
          } else {
            handleQuote(sb);
          }
        } else if (isEscapeChar(r)) {
          handleEscape(sb);

        } else if (!state.inQuotes) {
          
          if(r == separator) {
            toks.add(handleEndOfToken(sb));
          
          } else if (r == '\n') {
            // END OF RECORD
            break decide;
          
          } else if (r == '\r') {
            if ((r = reader.read()) == '\n') {
              // END OF RECORD
              break decide;
            } else {
              handleRegular(sb, '\r');
              continue decide;
            }
          
          } else {
            handleRegular(sb, (char) r);
          }
        } else {
          handleRegular(sb, (char) r);
        }

        r = reader.read();
      }

    // done parsing the line
    if (state.inQuotes && !allowedUnbalancedQuotes) {
      throw new IllegalArgumentException("Un-terminated quoted field at end of CSV record");
    }

    toks.add(handleEndOfToken(sb));
    return toks;
  }

  /**
   * @param data
   * @return the first record found
   */
  String[] parseFirstRecord(String data) throws IOException {
    return parseNthRecord(data, 1);
  }

  /**
   *
   * @param data
   * @param recordNumber Starts at 1, the record number to return.
   * @return
   */
  String[] parseNthRecord(String data, int recordNumber) throws IOException {
    if (data == null) {
      throw new IllegalArgumentException("I cannot parse a null string");
    }
    if (recordNumber <= 0) {
      throw new IllegalArgumentException("The record number must be greater than zero: " + recordNumber);
    }

    Reader r = new StringReader(data);

    List<String> toks;
    while (recordNumber > 1 && (toks = parseNext(r)) != null) {
      recordNumber--;
    }

    toks = parseNext(r);
    if (toks == null) {
      return null;
    } else {
      return toks.toArray(new String[toks.size()]);
    }
  }

  /* --------------------------------- */
  /* ---[ internal helper methods ]--- */
  /* --------------------------------- */
  boolean isEscapeChar(int c) {
    // if the escapechar is set to the ParserUtil.NULL_CHAR then it shouldn't
    // match anything => nothing is the escapechar
    return c == escapechar && escapechar != ParserUtil.NULL_CHARACTER;
  }

  boolean isQuoteChar(int c) {
    // if the quotechar is set to the ParserUtil.NULL_CHAR then it shouldn't
    // match anything => nothing is the quotechar
    return c == quotechar && quotechar != ParserUtil.NULL_CHARACTER;
  }

  String handleEndOfToken(StringBuilder sb) {
    // in strictQuotes mode you don't know when to add the last seen
    // quote until the token is done; if the buffer has any characters
    // then you know a first quote was seen, so add the closing quote
    if (strictQuotes && sb.length() > 0) {
      sb.append(quotechar);
    }
    String tok = trim(sb);
    state.escapeFound(false);
    sb.setLength(0);
    return tok;
  }

  void appendRegularChar(StringBuilder sb, char c) {
    if (state.inEscape && !retainEscapeChars) {
      switch (c) {
        case 'n':
          sb.append('\n');
          break;
        case 't':
          sb.append('\t');
          break;
        case 'r':
          sb.append('\r');
          break;
        case 'b':
          sb.append('\b');
          break;
        case 'f':
          sb.append('\f');
          break;
        default:
          sb.append(c);
          break;
      }
    } else {
      sb.append(c);
    }
    state.escapeFound(false);
  }

  void handleRegular(StringBuilder sb, char c) {
    if (strictQuotes) {
      if (state.inQuotes) {
        appendRegularChar(sb, c);
      }
    } else {
      appendRegularChar(sb, c);
    }
  }

  void handleEscape(StringBuilder sb) {
    state.escapeFound(true);
    if (retainEscapeChars) {
      if (strictQuotes) {
        if (state.inQuotes) {
          sb.append(escapechar);
        }
      } else {
        sb.append(escapechar);
      }
    }
  }

  void handleQuote(StringBuilder sb) {
    // always retain outer quotes while parsing and then remove them at the end if appropriate
    if (strictQuotes) {
      if (state.inQuotes) {
        if (state.inEscape) {
          sb.append(quotechar);
        }
      } else {
        // if buffer has nothing in it, then no quote has yet been seen
        // so this is the first one, thus add it 
        // (and remove later if don't want to retain outer quotes)
        if (sb.length() == 0) {
          sb.append(quotechar);
        }
      }

    } else {
      sb.append(quotechar);
    }
    state.quoteFound();
    state.escapeFound(false);
  }

  String trim(StringBuilder sb) {
    int left = 0;
    int right = sb.length() - 1;

    if (alwaysQuoteOutput) {
      if (trimWhiteSpace) {
        int[] indexes = ParserUtil.idxTrimSpaces(sb, left, right);
        left = indexes[0];
        right = indexes[1];
      }
      return ParserUtil.ensureQuoted(sb, left, right, quotechar);

    } else {
      if (!retainOuterQuotes) {
        if (trimWhiteSpace) {
          int[] indexes = ParserUtil.idxTrimSpaces(sb, left, right);
          left = indexes[0];
          right = indexes[1];

          indexes = ParserUtil.idxTrimEdgeQuotes(sb, left, right, quotechar);
          left = indexes[0];
          right = indexes[1];

          indexes = ParserUtil.idxTrimSpaces(sb, left, right);
          left = indexes[0];
          right = indexes[1];
        } else {
          ParserUtil.pluckOuterQuotes(sb, left, right, quotechar);
          left = 0;
          right = sb.length() - 1;
        }

      } else if (trimWhiteSpace) {
        int[] indexes = ParserUtil.idxTrimSpaces(sb, left, right);
        left = indexes[0];
        right = indexes[1];
      }
      return sb.substring(left, right + 1);
    }
  }
}