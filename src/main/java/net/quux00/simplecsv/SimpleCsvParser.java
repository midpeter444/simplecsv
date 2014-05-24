package net.quux00.simplecsv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
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
 *   - change the separator/delimiter char
 *   - change the quote char, including specifying no quote char by setting it to ParserUtil.NULL_CHARACTER
 *   - change the escape char, including specifying no escape by setting it to ParserUtil.NULL_CHARACTER
 *   - turn on strictQuotes mode
 *   - turn on trimWhitespace mode
 *   - turn on allowUnbalancedQuotes mode
 *   - turn off retainEscapeChars mode
 *   - turn on alwaysQuoteOutput mode
 * 
 * @NotThreadSafe - only use one CsvParser per thread
 */
public class SimpleCsvParser implements CsvParser {
  static final int INITIAL_READ_SIZE = 128;

  final char separator;
  final char quotechar;
  final char escapechar;
  final boolean strictQuotes;             // if true, characters outside the quotes are ignored
  final boolean trimWhiteSpace;           // if true, trim leading/trailing white space from tokens
  final boolean allowedUnbalancedQuotes;  // if true, allows unbalanced quotes in a token
  final boolean retainOuterQuotes;        // if true, outer quote chars are retained
  final boolean retainEscapeChars;        // if true, leaves escape chars in; if false removes them
  final boolean alwaysQuoteOutput;        // if true, put quote around around all outgoing tokens
  
  // used in parse()
  final State state = new State();
  final StringBuilder sb = new StringBuilder(INITIAL_READ_SIZE);
    
  public SimpleCsvParser() {
    separator = ParserUtil.DEFAULT_SEPARATOR;
    quotechar = ParserUtil.DEFAULT_QUOTE_CHAR;
    escapechar = ParserUtil.DEFAULT_ESCAPE_CHAR;
    strictQuotes = ParserUtil.DEFAULT_STRICT_QUOTES;
    trimWhiteSpace = ParserUtil.DEFAULT_TRIM_WS;
    allowedUnbalancedQuotes = ParserUtil.DEFAULT_ALLOW_UNBALANCED_QUOTES;
    retainOuterQuotes = ParserUtil.DEFAULT_RETAIN_OUTER_QUOTES;
    retainEscapeChars = ParserUtil.DEFAULT_RETAIN_ESCAPE_CHARS;
    alwaysQuoteOutput = ParserUtil.DEFAULT_ALWAYS_QUOTE_OUTPUT;
  }

  /**
   * Constructor with all options settable.  Unless you want the default behavior,
   * use the Builder to set the options you want.
   * @param separator  single char that separates values in the list
   * @param quotechar  single char that is used to quote values
   * @param escapechar  single char that is used to escape values
   * @param strictQuotes  setting to only accept values if they are between quotechars
   * @param trimWhiteSpace  trims leading and trailing whitespace of each token before it is returned
   * @param allowedUnbalancedQuotes  
   * @param retainOuterQuotes
   */
  public SimpleCsvParser(final char separator, final char quotechar, final char escapechar,
      final boolean strictQuotes, final boolean trimWhiteSpace, final boolean allowedUnbalancedQuotes,
      final boolean retainOuterQuotes, final boolean retainEscapeChars, final boolean alwaysQuoteOutput) 
  {
    this.separator = separator;
    this.quotechar = quotechar;
    this.escapechar = escapechar;
    this.strictQuotes = strictQuotes;
    this.trimWhiteSpace = trimWhiteSpace;
    this.allowedUnbalancedQuotes = allowedUnbalancedQuotes;
    this.retainOuterQuotes = retainOuterQuotes;
    this.retainEscapeChars = retainEscapeChars;
    this.alwaysQuoteOutput = alwaysQuoteOutput;
    
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
  static class State {
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

  
  /**
   * Retrieves a single line of text (as defined by the presence of LF or CRLF chars)
   * and parses it into tokens, returning it as an List of String.
   * 
   * If you are using the CsvParser directly (not through a CsvReader) then it is better
   * to use the {@link #parse(String)} method instead.
   * 
   * This method is used by the CsvReader. If the Reader passed in is not a BufferedReader
   * a BufferedReader is constructed to wrap the reader.
   * 
   * @param ln Single line of text to parse
   * @return parsed tokens as List<String>
   */
  public List<String> parseNext(Reader reader) throws IOException {
    String line = null;
    BufferedReader br = null;
    if (reader instanceof BufferedReader) {
      br = (BufferedReader) reader;
      line = br.readLine();
    } else {
      br = new BufferedReader(reader);
      line = br.readLine();
      br.close();
    }
    return parse0(line);
  }

  /**
   * Parses a single line of text (as defined by the presence of LF or CRLF chars)
   * according to the parser parameters you've set up and returns each parsed token
   * as an List of String.
   * 
   * @param ln Single line of text to parse
   * @return parsed tokens as List<String>
   */
  //public List<String> parse(String ln) {
  @Override
  public String[] parse(String ln) {
    if (ln == null) {
      return null;
    }
    List<String> toks = parse0(ln);
    return toks.toArray(new String[toks.size()]);
  }

  
  private List<String> parse0(String ln) {
    if (ln == null) { 
      return null; 
    }
    
    state.reset();
    sb.setLength(0);
    List<String> toks = new ArrayList<String>();  // returned to caller, so created afresh each time
    
    for (int i = 0; i < ln.length(); i++) {
      char c = ln.charAt(i);
      
      if (isQuoteChar(c)) {
        handleQuote(sb);
      
      } else if (isEscapeChar(c)) {
        handleEscape(sb);
      
      } else if (c == separator && !state.inQuotes) {
        toks.add( handleEndOfToken(sb) );
        
      } else {
        handleRegular(sb, c);
      }
    }
    
    // done parsing the line
    if (state.inQuotes && !allowedUnbalancedQuotes) {
      throw new IllegalArgumentException("Un-terminated quoted field at end of CSV line");
    }
    toks.add( handleEndOfToken(sb) );
    return toks;
  }  

  
  /* --------------------------------- */
  /* ---[ internal helper methods ]--- */
  /* --------------------------------- */
  
  boolean isEscapeChar(char c) {
    // if the escapechar is set to the ParserUtil.NULL_CHAR then it shouldn't
    // match anything => nothing is the escapechar
    return c == escapechar && escapechar != ParserUtil.NULL_CHARACTER;
  }
  
  boolean isQuoteChar(char c) {
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
      return sb.substring(left, right+1);
    }
  }
}