package com.facebook.imageutils;

public interface Continuation<TTaskResult, TContinuationResult> {
	TContinuationResult  then(Task<TTaskResult> task) throws Exception;
}
