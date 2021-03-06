/*******************************************************************************
 * Copyright 2012-2014 by Aerospike.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 ******************************************************************************/
package com.aerospike.examples;

import java.util.concurrent.atomic.AtomicInteger;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Log.Level;
import com.aerospike.client.Record;
import com.aerospike.client.async.AsyncClient;
import com.aerospike.client.command.Buffer;
import com.aerospike.client.listener.ExistsArrayListener;
import com.aerospike.client.listener.ExistsSequenceListener;
import com.aerospike.client.listener.RecordArrayListener;
import com.aerospike.client.listener.RecordSequenceListener;
import com.aerospike.client.listener.WriteListener;
import com.aerospike.client.util.Util;

public class AsyncBatch extends AsyncExample {
	
	private AsyncClient client;
	private Parameters params;
	private final String keyPrefix = "batchkey";
	private final String valuePrefix = "batchvalue";
	private Key[] sendKeys;
	private String binName;  
	private final int size = 8;
	private final AtomicInteger taskCount = new AtomicInteger();
	private int taskSize;
	private boolean completed;
	
	public AsyncBatch(Console console) {
		super(console);
	}

	/**
	 * Asynchronous batch examples.
	 */
	@Override
	public void runExample(AsyncClient client, Parameters params) throws Exception {
		this.client = client;
		this.params = params;
		this.binName = params.getBinName("batchbin");
		
		initializeKeys();
		writeRecords();
		waitTillComplete();
	}

	private void initializeKeys() throws AerospikeException {		
		sendKeys = new Key[size];
		
		for (int i = 0; i < size; i++) {
			sendKeys[i] = new Key(params.namespace, params.set, keyPrefix + (i + 1));
		}
	}
	
	/**
	 * Write records individually.
	 */
	private void writeRecords() throws Exception {		
		WriteHandler handler = new WriteHandler(size);
		
		for (int i = 1; i <= size; i++) {
			Key key = sendKeys[i-1];
			Bin bin = new Bin(binName, valuePrefix + i);
			
			console.info("Put: ns=%s set=%s key=%s bin=%s value=%s",
				key.namespace, key.setName, key.userKey, bin.name, bin.value);
			
			client.put(params.writePolicy, handler, key, bin);
		}
	}

	private class WriteHandler implements WriteListener {
		private final int max;
		private AtomicInteger count = new AtomicInteger();
		
		public WriteHandler(int max) {
			this.max = max;
		}
		
		public void onSuccess(Key key) {
			int rows = count.incrementAndGet();
			
			if (rows == max) {
				try {
					// All writes succeeded. Run batch queries in parallel.
					taskSize = 5;
					batchExistsArray();
					batchExistsSequence();
					batchGetArray();
					batchGetSequence();
					batchGetHeaders();
				}
				catch (Exception e) {				
					console.error("Batch failed: namespace=%s set=%s key=%s exception=%s", key.namespace, key.setName, key.userKey, e.getMessage());
				}
			}
		}
		
		public void onFailure(AerospikeException e) {
			console.error("Put failed: " + e.getMessage());
			allTasksComplete();
		}
	}
	
	/**
	 * Check existence of records in one batch, receive in one array.
	 */
	private void batchExistsArray() throws Exception {
		client.exists(params.policy, new ExistsArrayListener() {
			public void onSuccess(Key[] keys, boolean[] existsArray) {
				for (int i = 0; i < existsArray.length; i++) {
					Key key = keys[i];
					boolean exists = existsArray[i];
		            console.info("Record: ns=%s set=%s key=%s exists=%s",
		            	key.namespace, key.setName, key.userKey, exists);                        	
		        }
				taskComplete();
			}
			
			public void onFailure(AerospikeException e) {
				console.error("Batch exists array failed: " + Util.getErrorMessage(e));
				taskComplete();
			}			
		}, sendKeys);
    }
	
	/**
	 * Check existence of records in one batch, receive one record at a time.
	 */
	private void batchExistsSequence() throws Exception {
		client.exists(params.policy, new ExistsSequenceListener() {
			public void onExists(Key key, boolean exists) {
		        console.info("Record: ns=%s set=%s digest=%s exists=%s",
		            	key.namespace, key.setName, Buffer.bytesToHexString(key.digest), exists);
			}

			public void onSuccess() {
				taskComplete();
			}
			
			public void onFailure(AerospikeException e) {
				console.error("Batch exists sequence failed: " + Util.getErrorMessage(e));
				taskComplete();
			}			
		}, sendKeys);
    }

	/**
	 * Read records in one batch, receive in array.
	 */
	private void batchGetArray() throws Exception {
		client.get(params.policy, new RecordArrayListener() {
			public void onSuccess(Key[] keys, Record[] records) {
				for (int i = 0; i < records.length; i++) {
					Key key = keys[i];
					Record record = records[i];
					Level level = Level.ERROR;
					Object value = null;
					
					if (record != null) {
						level = Level.INFO;
						value = record.getValue(binName);
					}
			        console.write(level, "Record: ns=%s set=%s key=%s bin=%s value=%s",
			            key.namespace, key.setName, key.userKey, binName, value);
		        }
				
				if (records.length != size) {
		        	console.error("Record size mismatch. Expected %d. Received %d.", size, records.length);
				}
				taskComplete();
			}
			
			public void onFailure(AerospikeException e) {
				console.error("Batch get array failed: " + Util.getErrorMessage(e));
				taskComplete();
			}			
		}, sendKeys);
    }

	/**
	 * Read records in one batch call, receive one record at a time.
	 */
	private void batchGetSequence() throws Exception {
		client.get(params.policy, new RecordSequenceListener() {
			public void onRecord(Key key, Record record) {
				Level level = Level.ERROR;
				Object value = null;
				
				if (record != null) {
					level = Level.INFO;
					value = record.getValue(binName);
				}
		        console.write(level, "Record: ns=%s set=%s digest=%s bin=%s value=%s",
		            key.namespace, key.setName, Buffer.bytesToHexString(key.digest), binName, value);
			}
			
			public void onSuccess() {				
				taskComplete();
			}
			
			public void onFailure(AerospikeException e) {
				console.error("Batch get sequence failed: " + Util.getErrorMessage(e));
				taskComplete();
			}			
		}, sendKeys);
    }

	/**
	 * Read record headers in one batch, receive in an array.
	 */
	private void batchGetHeaders() throws Exception {
		client.getHeader(params.policy, new RecordArrayListener() {
			public void onSuccess(Key[] keys, Record[] records) {
				for (int i = 0; i < records.length; i++) {
					Key key = keys[i];
					Record record = records[i];
					Level level = Level.ERROR;
					int generation = 0;
					int expiration = 0;
					
					if (record != null && (record.generation > 0 || record.expiration > 0)) {
						level = Level.INFO;
						generation = record.generation;
						expiration = record.expiration;
					}
			        console.write(level, "Record: ns=%s set=%s key=%s generation=%d expiration=%d",
			            key.namespace, key.setName, key.userKey, generation, expiration);
		        }
				
				if (records.length != size) {
		        	console.error("Record size mismatch. Expected %d. Received %d.", size, records.length);
				}
				taskComplete();
			}
			
			public void onFailure(AerospikeException e) {
				console.error("Batch get headers failed: " + Util.getErrorMessage(e));
				taskComplete();
			}			
		}, sendKeys);
    }

	private synchronized void waitTillComplete() {
		while (! completed) {
			try {
				super.wait();
			}
			catch (InterruptedException ie) {
			}
		}
	}

	private void taskComplete() {
		if (taskCount.incrementAndGet() >= taskSize) {
			allTasksComplete();
		}
	}

	private synchronized void allTasksComplete() {
		completed = true;
		super.notify();
	}
}
