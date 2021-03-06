package com.ociweb.pronghorn.stage.scheduling;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.ThreadBasedCallerLookup;
import com.ociweb.pronghorn.stage.PronghornStage;

public abstract class StageScheduler {

	static final Logger logger = LoggerFactory.getLogger(StageScheduler.class);
	protected GraphManager graphManager;
	
	private ThreadLocal<Integer> callerId = new ThreadLocal<Integer>();
	
	public StageScheduler(GraphManager graphManager) {
		GraphManager.disableMutation(graphManager);
		this.graphManager = graphManager;		
		assert(initThreadChecking(graphManager));
	}

	private boolean initThreadChecking(final GraphManager graphManager) {
		Pipe.setThreadCallerLookup(new ThreadBasedCallerLookup(){

			@Override
			public int getCallerId() {
				Integer id = callerId.get();
				return null==id ? -1 : id.intValue();
			}

			@Override
			public int getProducerId(int pipeId) {				
				return GraphManager.getRingProducerId(graphManager, pipeId);
			}

			@Override
			public int getConsumerId(int pipeId) {
				return GraphManager.getRingConsumerId(graphManager, pipeId);
			}});
		
		return true;
	}

	protected void setCallerId(Integer caller) {
		callerId.set(caller);
	}
	
	protected void clearCallerId() {
		callerId.set(null);
	}
	
	protected boolean validShutdownState() {
		return GraphManager.validShutdown(graphManager);	
	}

	public abstract void startup();
	public abstract void shutdown();
	public abstract boolean awaitTermination(long timeout, TimeUnit unit);
	public abstract void awaitTermination(long timeout, TimeUnit unit, Runnable clean, Runnable dirty);
	public abstract boolean TerminateNow();

	

	private static int idealThreadCount() {
		return Runtime.getRuntime().availableProcessors()*2;
	}
	
	public static StageScheduler defaultScheduler(GraphManager gm) {
		
		final boolean threadLimitHard = true;//must make this a hard limit or we can saturate the system easily.
		final int scale = 2;
		
		int ideal = idealThreadCount();
		int threadLimit = ideal; 
		assert(threadLimit>0);
		final int countStages = GraphManager.countStages(gm);
		if ((threadLimit<=0) || (countStages > scale*ideal)) {
			//do not allow the ThreadPerStageScheduler to be used, we must group
			threadLimit = scale*ideal;//this must be large so give them a few more
		}
		
		if (threadLimit>=countStages) { 
				  logger.info("Threads in use {}, one per stage.", countStages);
		          return new ThreadPerStageScheduler(gm);
		} else {
				  logger.info("Threads in use {}, fixed limit with fixed script.", threadLimit);
				  return new ScriptedFixedThreadsScheduler(gm, threadLimit, threadLimitHard);
				  
				  //TODO: if we do not need to roll back to this before 2018 we should remove this line.
 		         // return new FixedThreadsScheduler(gm, threadLimit, threadLimitHard);
		}
	}

	public static StageScheduler threadPerStage(GraphManager gm) {
		return new ThreadPerStageScheduler(gm);
	}
	
	public static StageScheduler fixedThreads(GraphManager gm, int threadCountLimit, boolean isHardLimit) {
		return new FixedThreadsScheduler(gm, threadCountLimit, isHardLimit);
	}
	
}
