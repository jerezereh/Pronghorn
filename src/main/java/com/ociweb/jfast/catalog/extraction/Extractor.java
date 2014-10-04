package com.ociweb.jfast.catalog.extraction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class Extractor {

    private final int fieldDelimiter;
    private final byte[] recordDelimiter;
    private final int openQuote;
    private final int closeQuote;
    private final int escape;    
    private final long blockSize;

    final int tailPadding;  //padding required to ensure full length of tokens are not split across mapped blocks
    
    //the next char is a text because of the following text
    byte[]  TEXT_COMMA1 = ", ".getBytes();//TODO: these should not be here, instead recombine after populating the tree structure.
    byte[]  TEXT_COMMA2 = ",,,".getBytes(); //TODO: can be set externally to allow 1 char as text based on this pattern
    byte[]  TEXT_COMMA4 = ",,,,".getBytes();
        
    //TODO: add support for string literals as needed
    final byte[][] temp = new byte[][]{};

    
    //TODO: B, Based on this design build another that can parse JSON
    
    //Parsing order of priority
    //  1.  escape
    //  2.  quotes
    //  3.  record delimiter
    //  4.  field delimiter
    //  5.  data
    
    //zero copy and garbage free
    //visitor may do copy and may produce garbage

    public Extractor(int fieldDelimiter, byte[] recordDelimiter,
                     int openQuote, int closeQuote, int escape, int pageBits) {
    	
    	this.blockSize = (1l<<pageBits)-1;
    	
    	this.fieldDelimiter = fieldDelimiter;
        this.recordDelimiter = recordDelimiter;
        this.openQuote = openQuote;
        this.closeQuote = closeQuote;
        this.escape = escape;
        
        this.tailPadding =   Math.max(
                                recordDelimiter.length,
                                temp.length);
    }

    
    public void extract(FileChannel fileChannel, ExtractionVisitor visitor) throws IOException {
        MappedByteBuffer mappedBuffer;
        
        long fileSize = fileChannel.size();
        long position = 0;
        
        int loops = 20;//11;
        
        
        ExtractorWorkspace workspace = new ExtractorWorkspace(false, false, -1, false, 0);
        
        mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, position, Math.min(blockSize, fileSize-position));
        int padding = tailPadding;
        do {       
                        
            if (mappedBuffer.limit()+position==fileSize) {
                padding = 0;
            }
            
            visitor.openFrame();
            do {
                parse(mappedBuffer, visitor, workspace);
            } while (mappedBuffer.remaining()>padding);
            if (position+mappedBuffer.position()>=fileSize) {
                if (flushContent(mappedBuffer,visitor, workspace)) {
                    flushField(visitor, workspace);
                    flushRecord(visitor, mappedBuffer.position(), workspace);
                }
            }
            
            
            //notify the visitor that the buffer is probably going to change out from under them
            visitor.closeFrame();
            //only increment by exactly how many bytes were read assuming we started at zero
            //can only cut at the last known record start
            position+=workspace.getRecordStart();
           
            //reset workspace to re-read this record from the beginning
            workspace.reset();
            
            if (--loops<=0) { //hack to exit early
                break;
            }
            
            mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, position, Math.min(blockSize, fileSize-position));
            
        } while (position<fileSize);

    }
    
    
    public void extract(FileChannel fileChannel, ExtractionVisitor visitor1, ExtractionVisitor visitor2) throws IOException {
        MappedByteBuffer mappedBuffer;
        
        long fileSize = fileChannel.size();
        long position = 0;
        
        
        ExtractorWorkspace workspace1 = new ExtractorWorkspace(false, false, -1, false, 0);
        ExtractorWorkspace workspace2 = new ExtractorWorkspace(false, false, -1, false, 0);
        
        mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, position, Math.min(blockSize, fileSize-position));
        int padding = tailPadding;
        do {
            //the last go round must never use any padding, this padding is only needed when spanning two blocks.
            if (mappedBuffer.limit()+position==fileSize) {
                padding = 0;
            }
            
            visitor1.openFrame();
            do {
                parse(mappedBuffer, visitor1, workspace1);
            } while (mappedBuffer.remaining()>padding);
            if (position+mappedBuffer.position()>=fileSize) {
                if (flushContent(mappedBuffer,visitor1, workspace1)) {
                    flushField(visitor1, workspace1);
                    flushRecord(visitor1, mappedBuffer.position(), workspace1);
                }
            }
            
            visitor1.closeFrame();           
            workspace1.reset();//must be done after any calls for data in workspace
                                    
            //visit second visitor while this block is still mapped
            mappedBuffer.position(0);
            
            visitor2.openFrame();            
            do {
                parse(mappedBuffer, visitor2, workspace2);
            } while (mappedBuffer.remaining()>padding);
            //notify the visitor that the buffer is probably going to change out from under them
            visitor2.closeFrame();
            if (position+mappedBuffer.position()>=fileSize) {
                if (flushContent(mappedBuffer,visitor2, workspace2)) {
                    flushField(visitor2, workspace2);
                    flushRecord(visitor2, mappedBuffer.position(), workspace2);
                }
            }
            
            //only increment by exactly how many bytes were read assuming we started at zero
            position+=workspace2.getRecordStart();//Only done once by the last visitor for this data.
                        
            workspace2.reset();   //must be done after any calls for data in workspace                  
            
            mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, position, Math.min(blockSize, fileSize-position));
        } while (position<fileSize);
                
    }
    
    //TODO: When building JSON parser the field names will also be extracted. These names will be written one after the other into a buffer
    //      once the end if the message is reached this full string is used as an additional information point to distinquish between 
    //      messages that have the same field signatures but define them with different.  Each unique set of labels will need to define
    //      its own TypeTrie
    
    private boolean flushContent(MappedByteBuffer mappedBuffer, ExtractionVisitor visitor, ExtractorWorkspace workspace) {
        if (workspace.contentPos>=0 && mappedBuffer.position()>workspace.contentPos) {
            visitor.appendContent(mappedBuffer, workspace.contentPos, mappedBuffer.position(), workspace.contentQuoted);
            workspace.contentPos = -1;
            workspace.contentQuoted = false;
            return true;
        }
        return false;
    }

    private void flushRecord(ExtractionVisitor visitor, int pos, ExtractorWorkspace workspace) {
        
        
       visitor.closeRecord(workspace.getRecordStart());
       
       workspace.setRecordStart(pos+recordDelimiter.length);
       
    }

    private void flushField(ExtractionVisitor visitor, ExtractorWorkspace workspace) {
        visitor.closeField(workspace.getRecordStart());
    }


    private void parse(MappedByteBuffer mappedBuffer, ExtractionVisitor visitor, ExtractorWorkspace workspace) {
        parseEscape(mappedBuffer, visitor, workspace);
    }


    private void parseEscape(MappedByteBuffer mappedBuffer, ExtractionVisitor visitor, ExtractorWorkspace workspace) {
        if (mappedBuffer.get(mappedBuffer.position())==escape) { 
            if (workspace.inEscape) {
                //starts new content block from this location
                workspace.contentPos = mappedBuffer.position();
                workspace.contentQuoted = workspace.inQuote;
                workspace.inEscape = false;
            } else {
                flushContent(mappedBuffer, visitor, workspace);                
                workspace.inEscape = true;
            }
            mappedBuffer.position(mappedBuffer.position()+1);
        } else {
            parseQuote(mappedBuffer, visitor, workspace);
            workspace.inEscape = false;
        }
    }

    private void parseQuote(MappedByteBuffer mappedBuffer, ExtractionVisitor visitor, ExtractorWorkspace workspace) {
        if (workspace.inQuote) {
            if (mappedBuffer.get(mappedBuffer.position())==closeQuote) {
                if (workspace.inEscape) {
                    //starts new content block from this location
                    workspace.contentPos = mappedBuffer.position();
                    workspace.contentQuoted = workspace.inQuote;
                    workspace.inEscape = false;
                } else {                                
                    workspace.inQuote = false;  
                }
                mappedBuffer.position(mappedBuffer.position()+1);
            } else {
                parseRecord(mappedBuffer, visitor, workspace);   
            }
            
            
        } else {
            if (mappedBuffer.get(mappedBuffer.position())==openQuote) {
                if (workspace.inEscape) {
                    //starts new content block from this location
                    workspace.contentPos = mappedBuffer.position();
                    workspace.contentQuoted = workspace.inQuote;
                    workspace.inEscape = false;
                } else {
                    workspace.inQuote = true;
                }
                mappedBuffer.position(mappedBuffer.position()+1);
            } else {
                parseRecord(mappedBuffer, visitor, workspace);       
                
            }           
            
        }
    }
    
    
    private void parseRecord(MappedByteBuffer mappedBuffer, ExtractionVisitor visitor, ExtractorWorkspace workspace) {
        if (foundHere(mappedBuffer,recordDelimiter)) {
            if (workspace.inEscape) {
                //starts new content block from this location
                workspace.contentPos = mappedBuffer.position();
                workspace.contentQuoted = workspace.inQuote;
                workspace.inEscape = false;
            } else {
                if (workspace.inQuote) {
                    parseField(mappedBuffer, visitor, workspace);  
                } else {
                    flushContent(mappedBuffer, visitor, workspace);
                    flushField(visitor, workspace);
                    flushRecord(visitor, mappedBuffer.position(), workspace);
                }
            }
            mappedBuffer.position(mappedBuffer.position()+recordDelimiter.length);
        } else {
            parseField(mappedBuffer, visitor, workspace);       
            
        }           
    }
   
    //TODO: extract rules for continued content, add and NOT ,,,, and add that these 2 must alreay have content.
    private boolean mayBeEndOfField(MappedByteBuffer mappedBuffer, ExtractorWorkspace workspace) {
        return workspace.contentPos==-1 || //if no content so far this is just an empty field
                foundHere(mappedBuffer,TEXT_COMMA4) | //if lots of commas this is
               (!foundHere(mappedBuffer,TEXT_COMMA1) &&
                !(foundHere(mappedBuffer,TEXT_COMMA2)&&(mappedBuffer.position()-workspace.contentPos>7))    );
        
    }
    
    private void parseField(MappedByteBuffer mappedBuffer, ExtractionVisitor visitor, ExtractorWorkspace workspace) {
                
        if (mappedBuffer.get(mappedBuffer.position())==fieldDelimiter && mayBeEndOfField(mappedBuffer, workspace) ) {
            if (workspace.inEscape) {
                //starts new content block from this location
                workspace.contentPos = mappedBuffer.position();
                workspace.contentQuoted = workspace.inQuote;
                workspace.inEscape = false;
                mappedBuffer.position(mappedBuffer.position()+1);
            } else {
                if (workspace.inQuote) {
                    parseContent(mappedBuffer, workspace); 
                    mappedBuffer.position(mappedBuffer.position()+1);
                } else {                
                    flushContent(mappedBuffer, visitor, workspace);
                    flushField(visitor, workspace);
                    mappedBuffer.position(mappedBuffer.position()+1);
                    //the field has finished so check for the special case values //TODO: also do this on line beginning
                    
                    int j = temp.length;
                    while (--j>=0) {
                        if (foundHere(mappedBuffer,temp[j])) { 
                            
                            workspace.contentQuoted = false;
                            workspace.contentPos = mappedBuffer.position();
                            mappedBuffer.position(mappedBuffer.position()+temp[j].length);
                            j=-1;
                        }
                    }
                    
                    
                }
            }
           
        } else {
            parseContent(mappedBuffer, workspace); 
        }      
    }   
    
    
    static private void parseContent(MappedByteBuffer mappedBuffer, ExtractorWorkspace workspace) {        
        if (workspace.contentPos<0) {
            workspace.contentPos = mappedBuffer.position();
            workspace.contentQuoted = workspace.inQuote;
        }
        mappedBuffer.position(mappedBuffer.position()+1);
    }  
    

    private boolean foundHere(MappedByteBuffer data, byte[] goal) {
        
        int i = goal.length;
        if (data.position()+i>data.limit()) {
            return false;
        }
        while (--i>=0) {
            if (data.get(data.position()+i)!=goal[i]) {
                return false;
            }
        }
        return true;
    }
        
    
    
}
