package com.ociweb.jfast.stream;

import com.ociweb.jfast.error.FASTException;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.loader.TemplateCatalogConfig;
import com.ociweb.jfast.primitive.PrimitiveWriter;
import com.ociweb.jfast.primitive.adapter.FASTOutputByteArrayEquals;

public class FASTDynamicWriter {

    private final FASTWriterInterpreterDispatch writerDispatch;
    private final TemplateCatalogConfig catalog;
    private final int[] fullScript;
    private final FASTRingBuffer ringBuffer;
    private final PrimitiveWriter writer;

    final byte[] preambleData;

    public FASTDynamicWriter(PrimitiveWriter primitiveWriter, TemplateCatalogConfig catalog, FASTRingBuffer ringBuffer,
            FASTWriterInterpreterDispatch writerDispatch) {

        this.writerDispatch = writerDispatch;

        this.catalog = catalog;
        this.fullScript = catalog.fullScript();
        this.ringBuffer = ringBuffer;
        this.writer = primitiveWriter;

        this.preambleData = new byte[catalog.clientConfig().getPreableBytes()];
    }

    int msg = 0;
    // non blocking write, returns if there is nothing to do.
    public void write() {
        // write from the the queue/ringBuffer
        // queue will contain one full unit to be processed.
        // Unit: 1 Template/Message or 1 EntryInSequence

        // because writer does not move pointer up until full unit is ready to
        // go
        // we only need to check if data is available, not the size.
      //  if (ringBuffer.hasContent()) {
            int idx = 0;
            
            if (ringBuffer.isNewMessage) {
                msg++;
             //   System.err.println(msg);
                
                if (preambleData.length != 0) {

                    int i = 0;
                    int s = preambleData.length;
                    while (i < s) {
                        int d = FASTRingBufferReader.readInt(ringBuffer, idx);
                        preambleData[i++] = (byte) (0xFF & (d >>> 0));
                        preambleData[i++] = (byte) (0xFF & (d >>> 8));
                        preambleData[i++] = (byte) (0xFF & (d >>> 16));
                        preambleData[i++] = (byte) (0xFF & (d >>> 24));
                        idx++;
                    }
                    writerDispatch.writePreamble(preambleData, writer);
                };

                // template processing (can these be nested?)
              //  int templateId = FASTRingBufferReader.readInt(ringBuffer, idx);
                idx++;
                
//
//                // tokens - reading
//                writerDispatch.activeScriptCursor = catalog.getTemplateStartIdx()[templateId];
//                writerDispatch.activeScriptLimit = catalog.getTemplateLimitIdx()[templateId];
//
//                if (0 == writerDispatch.activeScriptLimit && 0 == writerDispatch.activeScriptCursor) {
//                    throw new FASTException("Unknown template:" + templateId);
//                }
//                // System.err.println("tmpl "+ringBuffer.remPos+"  templateId:"+templateId+" script:"+activeScriptCursor+"_"+activeScriptLimit);

            }

            //TODO: must write one fragment then poll again.
            int steps = ringBuffer.fragmentSteps();//-idx;
        //    System.err.println("steps "+steps);

     //       System.err.println("write fragment at:"+ringBuffer.cursor);
            
            writerDispatch.activeScriptCursor = ringBuffer.cursor;
            int stop = ringBuffer.cursor+steps;
            while (writerDispatch.activeScriptCursor<stop) { //TODO: A, this loop should be in dispatch same as the reader.
                int token = ringBuffer.from.tokens[writerDispatch.activeScriptCursor];
                writerDispatch.dispatchWriteByToken(idx, writer);


                
                int size = TypeMask.ringBufferFieldSize[TokenBuilder.extractType(token)];
            //    System.err.println(size+" for  "+TokenBuilder.tokenToString(token));
                
                //TODO: A, remove hack and integrate the idx position into writerDispatch.
                if (TokenBuilder.extractType(token)==TypeMask.Decimal ||
                    TokenBuilder.extractType(token)==TypeMask.DecimalOptional) {
                //    System.err.println("cursor" +writerDispatch.activeScriptCursor);
                    size+=2;//for the long we jumped over.
                }
                
                idx += size;    //can look up directly from ringBuffer!                       

                writerDispatch.activeScriptCursor++; 
            }

    }

    


    public void reset(boolean clearData) {

        writerDispatch.activeScriptCursor = 0;
        writerDispatch.activeScriptLimit = 0;

        if (clearData) {
            this.writerDispatch.reset();
        }
    }

}
