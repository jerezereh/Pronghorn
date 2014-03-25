//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.stream;

import com.ociweb.jfast.field.ByteHeap;
import com.ociweb.jfast.field.FieldReaderBytes;
import com.ociweb.jfast.field.FieldReaderChar;
import com.ociweb.jfast.field.FieldReaderDecimal;
import com.ociweb.jfast.field.FieldReaderInteger;
import com.ociweb.jfast.field.FieldReaderLong;
import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.TextHeap;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.primitive.PrimitiveReader;

//May drop interface if this causes a performance problem from virtual table
public class FASTReaderDispatch{
	
	private final PrimitiveReader reader;
	
	private int readFromIdx = -1; 
	
	//This is the GLOBAL dictionary
	//When unspecified in the template GLOBAL is the default so these are used.
	private final FieldReaderInteger readerInteger;
	private final FieldReaderLong    readerLong;
	private final FieldReaderDecimal readerDecimal;
	private final FieldReaderChar readerChar;
	private final FieldReaderBytes readerBytes;
			
    private final int nonTemplatePMapSize;
    private final int[][] dictionaryMembers;
	
	private final DictionaryFactory dictionaryFactory;
	
	//constant fields are always the same or missing but never anything else.
	//         manditory constant does not use pmap and has constant injected at destnation never xmit
	//         optional constant does use the pmap 1 (use initial const) 0 (not present)
	//
	//default fields can be the default or overridden this one time with a new value.

	int maxNestedSeqDepth = 64; //TODO: need value from template
	int[] sequenceCountStack = new int[maxNestedSeqDepth];
	int sequenceCountStackHead = -1;
	int checkSequence;
    int jumpSequence;
	
	int[] integerDictionary;
	long[] longDictionary;
	int[] decimalExponentDictionary;
	long[] decimalMantissaDictionary;
	TextHeap charDictionary;
	ByteHeap byteDictionary;
	
	public FASTReaderDispatch(PrimitiveReader reader, DictionaryFactory dcr, 
			                   int nonTemplatePMapSize, int[][] dictionaryMembers, int maxTextLen, 
			                   int maxVectorLen, int charGap, int bytesGap) {
		this.reader = reader;
		this.dictionaryFactory = dcr;
		this.nonTemplatePMapSize = nonTemplatePMapSize;
		this.dictionaryMembers = dictionaryMembers;
				
		this.longDictionary = dcr.longDictionary();
		this.integerDictionary = dcr.integerDictionary();
		this.decimalMantissaDictionary = dcr.decimalMantissaDictionary();
		this.decimalExponentDictionary = dcr.decimalExponentDictionary();
		this.charDictionary = dcr.charDictionary(maxTextLen,charGap);
		this.byteDictionary = dcr.byteDictionary(maxVectorLen,bytesGap);
		
		
		this.readerInteger = new FieldReaderInteger(reader,integerDictionary);
		this.readerLong = new FieldReaderLong(reader,longDictionary);
		this.readerDecimal = new FieldReaderDecimal(reader, decimalExponentDictionary,decimalMantissaDictionary);
		this.readerChar = new FieldReaderChar(reader,charDictionary);
		this.readerBytes = new FieldReaderBytes(reader,byteDictionary);
	}

	public void reset() {
		
		//System.err.println("total read fields:"+totalReadFields);
	//	totalReadFields = 0;
		
		//clear all previous values to un-set
		readerInteger.reset(dictionaryFactory);
		readerLong.reset(dictionaryFactory);
		readerDecimal.reset(dictionaryFactory);
		readerChar.reset();
		readerBytes.reset();
		sequenceCountStackHead = -1;
		
	}

	
	public TextHeap textHeap() {
		return readerChar.textHeap();
	}
	
	public ByteHeap byteHeap() {
		return readerBytes.byteHeap();
	}
	
	
	public void dispatchReadPrefix(byte[] target) {
		reader.readByteData(target, 0, target.length);
	}
	
//	long totalReadFields = 0;
	
	//package protected, unless we find a need to expose it?
	boolean dispatchReadByToken(int token, FASTRingBuffer outputQueue) {
	   //The nested IFs for this short tree are slightly faster than switch 
	   //for more JVM configurations and when switch is faster (eg lots of JVM -XX: args)
	   //it is only slightly faster.
		
	   //For a dramatic speed up of this dispatch code look into code generation of the
	   //script as a series of function calls against the specific FieldReader*.class
	   //This is expected to save 4ns per field on the AMD hardware or a speedup > 12%.
		
		//Yet another idea is to process two tokens together and add a layer of
		//mangled functions that have "pre-coded" scripts. What if we just repeat the same type?
						
	//	totalReadFields++;
		
		//THOUGHTS
		//Build fixed length and put all in ring buffer, consumers can
		//look at leading int to determine what kind of message they have
		//and the script position can be looked up by field id once for their needs.
		//each "mini-message is expected to be very small" and all in cache
		
		//System.err.println("---- write "+TokenBuilder.tokenToString(token)+" at "+outputQueue.addPos);
		
		if (0==(token&(16<<TokenBuilder.SHIFT_TYPE))) {
			//0????
			if (0==(token&(8<<TokenBuilder.SHIFT_TYPE))) {
				//00???
				if (0==(token&(4<<TokenBuilder.SHIFT_TYPE))) {
					outputQueue.appendInteger(dispatchReadByTokenForInteger(token));//int
				} else {
					outputQueue.appendLong(dispatchReadByTokenForLong(token));//long
				}
			} else {
				//01???
				if (0==(token&(4<<TokenBuilder.SHIFT_TYPE))) {
					//int for text					
					outputQueue.appendText(dispatchReadByTokenForText(token));				
				} else {
					//011??
					if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
						//0110? Decimal and DecimalOptional
						if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
							outputQueue.appendDecimal(readerDecimal.readDecimalExponent(token, -1), //TODO: must add support for decimal pulling last value from another dicitonary.
							                   		  readerDecimal.readDecimalMantissa(token, -1));
						} else {
							outputQueue.appendDecimal(readerDecimal.readDecimalExponentOptional(token, -1),
							    		       		  readerDecimal.readDecimalMantissaOptional(token, -1));
						}
					} else {
						outputQueue.append(dispatchReadByToken0111(token), byteDictionary);//int for bytes
					}
				}
			}
			return false;
		} else {
			//pause node for more work processing will return false 
			return dispatchReadByToken1(token, outputQueue);	
		}
	}

	private int dispatchReadByToken0111(int token) {
		//0111?
		if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
			//01110 ByteArray
			return readByteArray(token);
		} else {
			//01111 ByteArrayOptional
			return readByteArrayOptional(token);
		}
	}
	
	public int jumpSequence() {
		return jumpSequence;
	}
		
	private boolean dispatchReadByToken1(int token, FASTRingBuffer outputQueue) {
		//1????
		if (0==(token&(8<<TokenBuilder.SHIFT_TYPE))) {
			//10???
			if (0==(token&(4<<TokenBuilder.SHIFT_TYPE))) {
				//100??
				//Group Type, no others defined so no need to keep checking
				readGroupCommand(token);
				return 	checkSequence!=0 && completeSequence(token);	
				
			} else {
				//101??
				//Length Type, no others defined so no need to keep checking
				//Only happens once before a node sequence so push it on the count stack
				int length;
				outputQueue.appendInteger(length = readIntegerUnsigned(token));
				if (length==0) {
	//				System.err.println("testing squence length of zero"); //TODO: build test to cover this.
					jumpSequence = -1;
				} else {			
					jumpSequence = 0;
					sequenceCountStack[++sequenceCountStackHead] = length;
				}
			//	System.err.println("---- looping:"+length);
				
			}
		} else {
			//11???
			//Dictionary Type, no others defined so no need to keep checking
			readDictionaryCommand(token);
			
		}
		return false;
	}

	private void readDictionaryCommand(int token) {

		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			
			//need dictionary id?
			int dictionary = TokenBuilder.MAX_INSTANCE&token;

			int[] members = dictionaryMembers[dictionary];
			//System.err.println(members.length+" "+Arrays.toString(members));
			
			int m = 0;
			int limit = members.length;
			if (limit>0) {
				int idx = members[m++];
				while (m<limit) {
					assert(idx<0);
					
					if (0==(idx&8)) {
						if (0==(idx&4)) {
							//integer
							while (m<limit && (idx = members[m++])>=0) {
								readerInteger.reset(idx);
							}
						} else {
							//long
							while (m<limit && (idx = members[m++])>=0) {
								readerLong.reset(idx);
							}
						}
					} else {
						if (0==(idx&4)) {							
							//text
							while (m<limit && (idx = members[m++])>=0) {
								readerChar.reset(idx);
							}
						} else {
							if (0==(idx&2)) {								
								//decimal
								while (m<limit && (idx = members[m++])>=0) {
									readerDecimal.reset(idx);
								}
							} else {
								//bytes
								while (m<limit && (idx = members[m++])>=0) {
									readerBytes.reset(idx);
								}
							}
						}
					}	
				}
			}
			
		} else {
			//OperatorMask.Dictionary_Read_From  0001
			//next read will need to use this index to pull the right initial value.
			//after it is used it must be cleared/reset to -1
			readFromIdx = TokenBuilder.MAX_INSTANCE&token;
		}
	}
	
	private void readGroupCommand(int token) {
		if (0==(token&(OperatorMask.Group_Bit_Close<<TokenBuilder.SHIFT_OPER))) {
			//this is NOT a message/template so the non-template pmapSize is used.			
			openGroup(token, nonTemplatePMapSize);
		} else {
			closeGroup(token);
		}
	}


	private int dispatchReadByTokenForText(int token) {
	//	System.err.println(" CharToken:"+TokenBuilder.tokenToString(token));
		
		//010??
		if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
			//0100?
			if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
				//01000 TextASCII
				return 	readTextASCII(token);
			} else {
				//01001 TextASCIIOptional
				return 	readTextASCIIOptional(token);
			}
		} else {
			//0101?
			if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
				//01010 TextUTF8
				return 	readTextUTF8(token);
			} else {
				//01011 TextUTF8Optional
				return 	readTextUTF8Optional(token);
			}
		}
	}

	private long dispatchReadByTokenForLong(int token) {
		//001??
		if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
			//0010?
			if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
				//00100 LongUnsigned
				return readLongUnsigned(token);
			} else {
				//00101 LongUnsignedOptional
				return readLongUnsignedOptional(token);
			}
		} else {
			//0011?
			if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
				//00110 LongSigned
				return readLongSigned(token);
			} else {
				//00111 LongSignedOptional
				return readLongSignedOptional(token);
			}
		}
	}

	private int dispatchReadByTokenForInteger(int token) {
		//000??
		if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
			//0000?
			if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
				//00000 IntegerUnsigned
				return readIntegerUnsigned(token);
			} else {
				//00001 IntegerUnsignedOptional
				return readIntegerUnsignedOptional(token); 
			}
		} else {
			//0001?
			if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
				//00010 IntegerSigned
				return	readIntegerSigned(token);
			} else {
				//00011 IntegerSignedOptional
				return	readIntegerSignedOptional(token);
			}
		}
	}
	
	public long readLong(int token) {
				
		assert(0!=(token&(4<<TokenBuilder.SHIFT_TYPE)));
		
		if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {//compiler does all the work.
			//not optional
			if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) { 
				return readLongUnsigned(token);
			} else {
				return readLongSigned(token);
			}
		} else {
			//optional
			if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
				return readLongUnsignedOptional(token);
			} else {
				return readLongSignedOptional(token);
			}	
		}
		
	}

	private long readLongSignedOptional(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					return  readerLong.readLongSignedOptional(token, readFromIdx);
				} else {
					//delta
					return  readerLong.readLongSignedDeltaOptional(token, readFromIdx );
				}	
			} else {
				//constant
				return  readerLong.readLongSignedConstantOptional(token, readFromIdx );
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return  readerLong.readLongSignedCopyOptional(token, readFromIdx );
				} else {
					//increment
					return  readerLong.readLongSignedIncrementOptional(token, readFromIdx );
				}	
			} else {
				// default
				return  readerLong.readLongSignedDefaultOptional(token, readFromIdx );
			}		
		}
		
	}

	private long readLongSigned(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					return  readerLong.readLongSigned(token, readFromIdx);
				} else {
					//delta
					return  readerLong.readLongSignedDelta(token, readFromIdx);
				}	
			} else {
				//constant
				return  readerLong.readLongSignedConstant(token, readFromIdx);
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return  readerLong.readLongSignedCopy(token, readFromIdx);
				} else {
					//increment
					return  readerLong.readLongSignedIncrement(token, readFromIdx);	
				}	
			} else {
				// default
				return  readerLong.readLongSignedDefault(token, readFromIdx);
			}		
		}
	}

	private long readLongUnsignedOptional(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					return  readerLong.readLongUnsignedOptional(token, readFromIdx);
				} else {
					//delta
					return  readerLong.readLongUnsignedDeltaOptional(token, readFromIdx);
				}	
			} else {
				//constant
				return  readerLong.readLongUnsignedConstantOptional(token, readFromIdx);
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return  readerLong.readLongUnsignedCopyOptional(token, readFromIdx);
				} else {
					//increment
					return  readerLong.readLongUnsignedIncrementOptional(token, readFromIdx);
				}	
			} else {
				// default
				return  readerLong.readLongUnsignedDefaultOptional(token, readFromIdx);
			}		
		}

	}

	private long readLongUnsigned(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					return  readerLong.readLongUnsigned(token, readFromIdx);
				} else {
					//delta
					return  readerLong.readLongUnsignedDelta(token, readFromIdx);
				}	
			} else {
				//constant
				return  readerLong.readLongUnsignedConstant(token, readFromIdx);
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return  readerLong.readLongUnsignedCopy(token, readFromIdx);
				} else {
					//increment
					return  readerLong.readLongUnsignedIncrement(token, readFromIdx);		
				}	
			} else {
				// default
				return  readerLong.readLongUnsignedDefault(token, readFromIdx);
			}		
		}
		
	}

	public int readInt(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {//compiler does all the work.
			//not optional
			if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) { 
				return readIntegerUnsigned(token);
			} else {
				return readIntegerSigned(token);
			}
		} else {
			//optional
			if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
				return readIntegerUnsignedOptional(token);
			} else {
				return readIntegerSignedOptional(token);
			}	
		}		
	}

	private int readIntegerSignedOptional(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					return readerInteger.readIntegerSignedOptional(token,readFromIdx);
				} else {
					//delta
					return readerInteger.readIntegerSignedDeltaOptional(token,readFromIdx);
				}	
			} else {
				//constant
				return readerInteger.readIntegerSignedConstantOptional(token,readFromIdx);
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return readerInteger.readIntegerSignedCopyOptional(token,readFromIdx);
				} else {
					//increment
					return readerInteger.readIntegerSignedIncrementOptional(token,readFromIdx);
				}	
			} else {
				// default
				return readerInteger.readIntegerSignedDefaultOptional(token,readFromIdx);
			}		
		}
		
	}

	private int readIntegerSigned(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					return readerInteger.readIntegerSigned(token,readFromIdx);
				} else {
					//delta
					return readerInteger.readIntegerSignedDelta(token,readFromIdx);
				}	
			} else {
				//constant
				return readerInteger.readIntegerSignedConstant(token,readFromIdx);
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return readerInteger.readIntegerSignedCopy(token,readFromIdx);
				} else {
					//increment
					return readerInteger.readIntegerSignedIncrement(token,readFromIdx);	
				}	
			} else {
				// default
				return readerInteger.readIntegerSignedDefault(token,readFromIdx);
			}		
		}
	}

	private int readIntegerUnsignedOptional(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					assert(readFromIdx<0);
					return readerInteger.readIntegerUnsignedOptional(token);
				} else {
					//delta
					return readerInteger.readIntegerUnsignedDeltaOptional(token,readFromIdx);
				}	
			} else {
				//constant
				return readerInteger.readIntegerUnsignedConstantOptional(token,readFromIdx);
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return readerInteger.readIntegerUnsignedCopyOptional(token,readFromIdx);
				} else {
					//increment
					return readerInteger.readIntegerUnsignedIncrementOptional(token,readFromIdx);	
				}	
			} else {
				// default
				return readerInteger.readIntegerUnsignedDefaultOptional(token,readFromIdx);
			}		
		}
	
	}

	private int readIntegerUnsigned(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
			//none, constant, delta
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//none, delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//none
					assert(readFromIdx<0);
					return readerInteger.readIntegerUnsigned(token);
				} else {
					//delta
					return readerInteger.readIntegerUnsignedDelta(token,readFromIdx);
				}	
			} else {
				//constant
				return readerInteger.readIntegerUnsignedConstant(token,readFromIdx);
			}
			
		} else {
			//copy, default, increment
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
				//copy, increment
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//copy
					return readerInteger.readIntegerUnsignedCopy(token,readFromIdx);
				} else {
					//increment
					return readerInteger.readIntegerUnsignedIncrement(token,readFromIdx);
				}	
			} else {
				// default
				return readerInteger.readIntegerUnsignedDefault(token,readFromIdx);
			}		
		}
	}

	public int readBytes(int token) {
				
		assert(0!=(token&(4<<TokenBuilder.SHIFT_TYPE)));
		assert(0!=(token&(8<<TokenBuilder.SHIFT_TYPE)));
		
	//	System.out.println("reading "+TokenBuilder.tokenToString(token));
		
		if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {//compiler does all the work.
			return readByteArray(token);
		} else {
			return readByteArrayOptional(token);
		}
	}

	private int readByteArray(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
			//none constant delta tail 
			if (0==(token&(6<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//none tail
				if (0==(token&(8<<TokenBuilder.SHIFT_OPER))) {
					//none
					return readerBytes.readBytes(token, readFromIdx);
				} else {
					//tail
					return readerBytes.readBytesTail(token, readFromIdx);
				}
			} else {
				// constant delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//constant
					return readerBytes.readBytesConstant(token, readFromIdx);
				} else {
					//delta
					return readerBytes.readBytesDelta(token, readFromIdx);
				}
			}
		} else {
			//copy default
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//copy
				return readerBytes.readBytesCopy(token, readFromIdx);
			} else {
				//default
				return readerBytes.readBytesDefault(token, readFromIdx);
			}
		}
	}
	
	private int readByteArrayOptional(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
			//none constant delta tail 
			if (0==(token&(6<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//none tail
				if (0==(token&(8<<TokenBuilder.SHIFT_OPER))) {
					//none
					return readerBytes.readBytesOptional(token, readFromIdx);
				} else {
					//tail
					return readerBytes.readBytesTailOptional(token, readFromIdx);
				}
			} else {
				// constant delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//constant
					return readerBytes.readBytesConstantOptional(token, readFromIdx);
				} else {
					//delta
					return readerBytes.readBytesDeltaOptional(token, readFromIdx);
				}
			}
		} else {
			//copy default
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//copy
				return readerBytes.readBytesCopyOptional(token, readFromIdx);
			} else {
				//default
				return readerBytes.readBytesDefaultOptional(token, readFromIdx);
			}
		}
	}
	
	public int openMessage(int pmapMaxSize) {
		
		reader.openPMap(pmapMaxSize);
		//return template id or unknown
		return (0!=reader.popPMapBit()) ? reader.readIntegerUnsigned() : -1;//template Id

	}
	
	public void closeMessage() {
		reader.closePMap();
	}
	

	
	
	public void openGroup(int token, int pmapSize) {

		assert(token<0);
		assert(0==(token&(OperatorMask.Group_Bit_Close<<TokenBuilder.SHIFT_OPER)));
			
		if (pmapSize>0) {
			reader.openPMap(pmapSize);
		}
	
	//	beginSequence(token);
			
		
//		if (0!=(token&(OperatorMask.Group_Bit_Templ<<TokenBuilder.SHIFT_OPER))) { //TODO:pull from operator!
//			//always push something on to the stack
//			int newTop = (reader.popPMapBit()!=0) ? reader.readIntegerUnsigned() : templateStack[templateStackHead];
//			templateStack[templateStackHead++] = newTop;
//
//		}
	}

//	//called after open group.?? or just near.
//	public boolean beginSequence(int token) {
//		//We repeat the open for each sequence but we do not want to push on
//		//to the seq stack. Upon jump back the token will mask out the seq bit.
//		if (
////			 0==(token&((8|4)<<TokenBuilder.SHIFT_TYPE)) &&
//	//		 0==(token&(OperatorMask.Group_Bit_Close<<TokenBuilder.SHIFT_OPER)) &&
//				
//			 0!=(token&(OperatorMask.Group_Bit_Seq<<TokenBuilder.SHIFT_OPER))) {
//			//System.err.println("starting new sequence ");
//			return true;//started new sequence
//		}
//		return false;//no sequence to start
//	}


	/**
	 * Returns true if there is no sequence in play or if the active sequence can be closed.
	 * Once a sequence is closed the reader should move to the next point in the sequence. 
	 * 
	 * @param token
	 * @return
	 */
	public boolean completeSequence(int token) {
		
		checkSequence = 0;//reset for next time
		
		if (sequenceCountStackHead<=0) {
			//no sequence to worry about or not the right time
			return false;
		}
		
		
		//each sequence will need to repeat the pmap but we only need to push
		//and pop the stack when the sequence is first encountered.
		//if count is zero we can pop it off but not until then.
		
		if (--sequenceCountStack[sequenceCountStackHead]<1) {
			//this group is a sequence so pop it off the stack.
			//System.err.println("finished seq");
			--sequenceCountStackHead;
			//finished this sequence so leave pointer where it is
			jumpSequence= 0;
		} else {
			//do this sequence again so move pointer back
			jumpSequence = (TokenBuilder.MAX_INSTANCE&token);
		}
		return true;
	}
	
	public void closeGroup(int token) {
		
		assert(token<0);
		assert(0!=(token&(OperatorMask.Group_Bit_Close<<TokenBuilder.SHIFT_OPER)));
		
		if (0!=(token&(OperatorMask.Group_Bit_PMap<<TokenBuilder.SHIFT_OPER))) {
			reader.closePMap();
		}
		
		checkSequence = (token&(OperatorMask.Group_Bit_Seq<<TokenBuilder.SHIFT_OPER));
		
	}

	public int readDecimalExponent(int token) {
		assert(0==(token&(2<<TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
		assert(0!=(token&(4<<TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
		assert(0!=(token&(8<<TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);

		if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
			return readerDecimal.readDecimalExponent(token, readFromIdx);
		} else {
			return readerDecimal.readDecimalExponentOptional(token, readFromIdx);
		}
	}
	

	public long readDecimalMantissa(int token) {
		
		assert(0==(token&(2<<TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
		assert(0!=(token&(4<<TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
		assert(0!=(token&(8<<TokenBuilder.SHIFT_TYPE))) : TokenBuilder.tokenToString(token);
		
		if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {
			return readerDecimal.readDecimalMantissa(token, readFromIdx);
		} else {
			return readerDecimal.readDecimalMantissaOptional(token, readFromIdx);
		}
	}

	public int readText(int token) {
		assert(0==(token&(4<<TokenBuilder.SHIFT_TYPE)));
		assert(0!=(token&(8<<TokenBuilder.SHIFT_TYPE)));
		
		if (0==(token&(1<<TokenBuilder.SHIFT_TYPE))) {//compiler does all the work.
			if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
				//ascii
				//System.err.println("read ascii");
				return readTextASCII(token);
			} else {
				//utf8
				//System.err.println("read utf8");
				return readTextUTF8(token);
			}
		} else {
			if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
				//ascii optional
				//System.err.println("read ascii opp");
				return readTextASCIIOptional(token);
			} else {
				//utf8 optional
				//System.err.println("read utf8 opp");
				return readTextUTF8Optional(token);
			}
		}
	}

	private int readTextUTF8Optional(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
			//none constant delta tail 
			if (0==(token&(6<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//none tail
				if (0==(token&(8<<TokenBuilder.SHIFT_OPER))) {
					//none
					//System.err.println("none");
					return readerChar.readUTF8Optional(token,readFromIdx);
				} else {
					//tail
					//System.err.println("tail");
					return readerChar.readUTF8TailOptional(token,readFromIdx);
				}
			} else {
				// constant delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//constant
					//System.err.println("const");
					return readerChar.readUTF8ConstantOptional(token,readFromIdx);
				} else {
					//delta
					//System.err.println("delta");
					return readerChar.readUTF8DeltaOptional(token,readFromIdx);
				}
			}
		} else {
			//copy default
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//copy
				//System.err.println("copy");
				return readerChar.readUTF8CopyOptional(token,readFromIdx);
			} else {
				//default
				//System.err.println("default");
				return readerChar.readUTF8DefaultOptional(token,readFromIdx);
			}
		}
		
	}

	private int readTextASCII(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
			//none constant delta tail 
			if (0==(token&(6<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//none tail
				if (0==(token&(8<<TokenBuilder.SHIFT_OPER))) {
					//none
					return readerChar.readASCII(token,readFromIdx);
				} else {
					//tail
					return readerChar.readASCIITail(token,readFromIdx);
				}
			} else {
				// constant delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//constant
					return readerChar.readASCIIConstant(token,readFromIdx);
				} else {
					//delta
					return readerChar.readASCIIDelta(token,readFromIdx);
				}
			}
		} else {
			//copy default
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//copy
				return readerChar.readASCIICopy(token,readFromIdx);
			} else {
				//default
				return readerChar.readASCIIDefault(token,readFromIdx);
			}
		}
	}

	private int readTextUTF8(int token) {
		
		if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
			//none constant delta tail 
			if (0==(token&(6<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//none tail
				if (0==(token&(8<<TokenBuilder.SHIFT_OPER))) {
					//none
				//	System.err.println("none");
					return readerChar.readUTF8(token,readFromIdx);
				} else {
					//tail
				//	System.err.println("tail");
					return readerChar.readUTF8Tail(token,readFromIdx);
				}
			} else {
				// constant delta
				if (0==(token&(4<<TokenBuilder.SHIFT_OPER))) {
					//constant
				//	System.err.println("const");
					return readerChar.readUTF8Constant(token,readFromIdx);
				} else {
					//delta
				//	System.err.println("delta read");
					return readerChar.readUTF8Delta(token,readFromIdx);
				}
			}
		} else {
			//copy default
			if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {//compiler does all the work.
				//copy
				//System.err.println("copy");
				return readerChar.readUTF8Copy(token,readFromIdx);
			} else {
				//default
				//System.err.println("default");
				return readerChar.readUTF8Default(token,readFromIdx);
			}
		}
		
	}

	private int readTextASCIIOptional(int token) {
		
		if (0==(token&((4|2|1)<<TokenBuilder.SHIFT_OPER))) {
			if (0==(token&(8<<TokenBuilder.SHIFT_OPER))) {
				//none
				return readerChar.readASCII(token,readFromIdx);
			} else {
				//tail
				return readerChar.readASCIITailOptional(token,readFromIdx);
			}
		} else {
			if (0==(token&(1<<TokenBuilder.SHIFT_OPER))) {
				if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
					return readerChar.readASCIIDeltaOptional(token,readFromIdx);
				} else {
					return readerChar.readASCIIConstantOptional(token,readFromIdx);
				}		
			} else {
				if (0==(token&(2<<TokenBuilder.SHIFT_OPER))) {
					return readerChar.readASCIICopyOptional(token,readFromIdx);
				} else {
					return readerChar.readASCIIDefaultOptional(token,readFromIdx);
				}
				
			}
		}
		
	}

	public boolean isEOF() {
		return reader.isEOF();
	}




}
