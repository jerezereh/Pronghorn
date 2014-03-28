//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.field;

import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.primitive.PrimitiveReader;

public class FieldReaderLong {
	
	private final int INSTANCE_MASK;
	private final PrimitiveReader reader;
	final long[]  lastValue; 
	
	
	public FieldReaderLong(PrimitiveReader reader, long[] values) {
		
		assert(values.length<TokenBuilder.MAX_INSTANCE);
		assert(FieldReaderInteger.isPowerOfTwo(values.length));
		
		this.INSTANCE_MASK = Math.min(TokenBuilder.MAX_INSTANCE, (values.length-1));
		
		this.reader = reader;
		this.lastValue = values;
	}
	
    /**
     * Computes the absent values as needed.
     * 00 ->  1
     * 01 ->  0
     * 10 -> -1
     * 11 -> TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_LONG
     * 
     * 0
     * 1
     * 1111111111111111111111111111111111111111111111111111111111111111
     * 111111111111111111111111111111111111111111111111111111111111111
     * 
     * @param b
     * @return
     */
    static long absentValue(int b) {
    	return ((1|(0l-(b>>1)))>>>(1&b));  	
    }
	
	public void reset(DictionaryFactory df) {
		df.reset(lastValue);
	}
	public void copy(int sourceToken, int targetToken) {
		lastValue[targetToken & INSTANCE_MASK] = lastValue[sourceToken & INSTANCE_MASK];
	}

	public long readLongUnsigned(int token, int readFromIdx) {
		//no need to set initValueFlags for field that can never be null
		return lastValue[token & INSTANCE_MASK] = reader.readLongUnsigned();
	}

	public long readLongUnsignedOptional(int token, int readFromIdx) {
		long value = reader.readLongUnsigned();
		if (0==value) {
			return absentValue(TokenBuilder.extractAbsent(token));
		} else {
			return --value;
		}
	}

	public long readLongUnsignedConstant(int token, int readFromIdx) {
		//always return this required value.
		return lastValue[token & INSTANCE_MASK];
	}
	
	public long readLongUnsignedConstantOptional(int token, int readFromIdx) {
		return (reader.popPMapBit()==0 ? absentValue(TokenBuilder.extractAbsent(token)) : lastValue[token & INSTANCE_MASK]);
	}

	public long readLongSignedConstant(int token, int readFromIdx) {
		//always return this required value.
		return lastValue[token & INSTANCE_MASK];
	}
	
	public long readLongSignedConstantOptional(int token, int readFromIdx) {
		return (reader.popPMapBit()==0 ? absentValue(TokenBuilder.extractAbsent(token)) : lastValue[token & INSTANCE_MASK]);
	}

	public long readLongUnsignedCopy(int token, int readFromIdx) {
		return (reader.popPMapBit()==0 ? 
				 lastValue[token & INSTANCE_MASK] : 
			     (lastValue[token & INSTANCE_MASK] = reader.readLongUnsigned()));
	}

	public long readLongUnsignedCopyOptional(int token, int readFromIdx) {
		long value;
		if (reader.popPMapBit()==0) {
			value = lastValue[token & INSTANCE_MASK];
		} else {
			lastValue[token & INSTANCE_MASK] = value = reader.readLongUnsigned();
		}
		return (0 == value ? absentValue(TokenBuilder.extractAbsent(token)): value-1);
	}
	
	
	public long readLongUnsignedDelta(int token, int readFromIdx) {
		//Delta opp never uses PMAP
		return lastValue[token & INSTANCE_MASK] += reader.readLongSigned();
	}
	
	public long readLongUnsignedDeltaOptional(int token, int readFromIdx) {
		//Delta opp never uses PMAP
		long value = reader.readLongSigned();
		if (0==value) {
			lastValue[token & INSTANCE_MASK]=0;
			return absentValue(TokenBuilder.extractAbsent(token));
		} else {
			return lastValue[token & INSTANCE_MASK] += (value-1);
			
		}

	}

	public long readLongUnsignedDefault(int token, int readFromIdx) {
		if (reader.popPMapBit()==0) {
			//default value 
			return lastValue[token & INSTANCE_MASK];
		} else {
			//override value, but do not replace the default
			return reader.readLongUnsigned();
		}
	}

	public long readLongUnsignedDefaultOptional(int token, int readFromIdx) {
		if (reader.popPMapBit()==0) {
			
			long last = lastValue[token & INSTANCE_MASK];
			return 0==last?absentValue(TokenBuilder.extractAbsent(token)):last;
		} else {
			long value;
			if ((value = reader.readLongUnsigned())==0) {
				return absentValue(TokenBuilder.extractAbsent(token));
			} else {
				return value-1;
			}
		}
	}

	public long readLongUnsignedIncrement(int token, int readFromIdx) {
		
		if (reader.popPMapBit()==0) {
			//increment old value
			return ++lastValue[token & INSTANCE_MASK];
		} else {
			//assign and return new value
			return lastValue[token & INSTANCE_MASK] = reader.readLongUnsigned();
		}
	}


	public long readLongUnsignedIncrementOptional(int token, int readFromIdx) {
		int instance = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			return (lastValue[instance] == 0 ? absentValue(TokenBuilder.extractAbsent(token)): ++lastValue[instance]);
		} else {
			long value = reader.readLongUnsigned();
			if (value==0) {
				lastValue[instance] = 0;
				return absentValue(TokenBuilder.extractAbsent(token));
			} else {
				return (lastValue[instance] = value)-1;
			}
		}
	}

	//////////////
	//////////////
	//////////////
	
	public long readLongSigned(int token, int readFromIdx) {
		//no need to set initValueFlags for field that can never be null
		return lastValue[token & INSTANCE_MASK] = reader.readLongSigned();
	}

	public long readLongSignedOptional(int token, int readFromIdx) {
		long value = reader.readLongSigned();
		lastValue[token & INSTANCE_MASK] = value;//needed for dynamic read behavior.
		if (0==value) {
			return absentValue(TokenBuilder.extractAbsent(token));
		} else {
			return value-1;
		}
	}

	public long readLongSignedCopy(int token, int readFromIdx) {
		return (reader.popPMapBit()==0 ? 
				 lastValue[token & INSTANCE_MASK] : 
			     (lastValue[token & INSTANCE_MASK] = reader.readLongSigned()));
	}

	public long readLongSignedCopyOptional(int token, int readFromIdx) {
		//if zero then use old value.
		long value;
		if (reader.popPMapBit()==0) {
			value = lastValue[token & INSTANCE_MASK];
		} else {
			lastValue[token & INSTANCE_MASK] = value = reader.readLongSigned();
		}
		return (0 == value ? absentValue(TokenBuilder.extractAbsent(token)): (value>0 ? value-1 : value));
	}
	
	
	public long readLongSignedDelta(int token, int readFromIdx) {
		//Delta opp never uses PMAP
		return lastValue[token & INSTANCE_MASK]+=reader.readLongSigned();
		
	}
	
	public long readLongSignedDeltaOptional(int token, int readFromIdx) {
		//Delta opp never uses PMAP
		long value = reader.readLongSigned();
		if (0==value) {
			lastValue[token & INSTANCE_MASK] = 0;
			return absentValue(TokenBuilder.extractAbsent(token));
					//absentLongs[TokenBuilder.extractAbsent(token)];
		} else {
			return lastValue[token & INSTANCE_MASK] += 
					(value + ((value>>>63)-1) );
					//(value>0 ? value-1 : value);
		}

	}

	public long readLongSignedDefault(int token, int readFromIdx) {
		if (reader.popPMapBit()==0) {
			//default value 
			return lastValue[token & INSTANCE_MASK];
		} else {
			//override value, but do not replace the default
			return reader.readLongSigned();
		}
	}

	public long readLongSignedDefaultOptional(int token, int readFromIdx) {
		if (reader.popPMapBit()==0) {
			long last = lastValue[token & INSTANCE_MASK];
			return 0==last?absentValue(TokenBuilder.extractAbsent(token)):last;			
		} else {
			long value;
			if ((value = reader.readLongSigned())==0) {
				return absentValue(TokenBuilder.extractAbsent(token));
			} else {
				return value>0? value-1 : value;
			}
		}
	}

	public long readLongSignedIncrement(int token, int readFromIdx) {
		if (reader.popPMapBit()==0) {
			//increment old value
			return ++lastValue[token & INSTANCE_MASK];
		} else {
			//assign and return new value
			return lastValue[token & INSTANCE_MASK] = reader.readLongSigned();
		}
	}


	public long readLongSignedIncrementOptional(int token, int readFromIdx) {
		int instance = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			return (lastValue[instance] == 0 ? absentValue(TokenBuilder.extractAbsent(token)): ++lastValue[instance]);
		} else {
			long value;
			if ((lastValue[instance] = value = reader.readLongSigned())==0) {
				return absentValue(TokenBuilder.extractAbsent(token));
			} else {
				//lastValue[instance] = value;
				//return (value + (value>>>63))-1;
				return value>0 ? value-1 : value;
			}
		
		}
		
	}

	public void reset(int idx) {
		lastValue[idx] = 0;
	}
	
	
}
