/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import java.util.Map;
import java.util.concurrent.Executor;

import android.graphics.Bitmap;
import android.support.annotation.Nullable;

import com.facebook.common.internal.ImmutableMap;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.CloseableStaticBitmap;
import com.facebook.imagepipeline.request.Postprocessor;
import com.facebook.imagepipeline.request.RepeatedPostprocessor;
import com.facebook.imagepipeline.request.RepeatedPostprocessorRunner;

/**
 * Runs a caller-supplied post-processor object.
 * 
 * <p>
 * Post-processors are only supported for static bitmaps. If the request is for
 * an animated image, the post-processor step will be skipped without warning.
 */
public class PostprocessorProducer implements Producer<CloseableReference<CloseableImage>> {

	@VisibleForTesting
	static final String NAME = "PostprocessorProducer";
	@VisibleForTesting
	static final String POSTPROCESSOR = "Postprocessor";

	private final Producer<CloseableReference<CloseableImage>> mNextProducer;
	private final PlatformBitmapFactory mBitmapFactory;
	private final Executor mExecutor;

	public PostprocessorProducer(Producer<CloseableReference<CloseableImage>> nextProducer,
			PlatformBitmapFactory platformBitmapFactory, Executor executor) {
		mNextProducer = Preconditions.checkNotNull(nextProducer);
		mBitmapFactory = platformBitmapFactory;
		mExecutor = Preconditions.checkNotNull(executor);
	}

	@Override
	public void produceResults(final Consumer<CloseableReference<CloseableImage>> consumer, ProducerContext context) {
		final ProducerListener listener = context.getListener();
		final Postprocessor postprocessor = context.getImageRequest().getPostprocessor();
		final PostprocessorConsumer basePostprocessorConsumer = new PostprocessorConsumer(consumer, listener,
				context.getId(), postprocessor, context);
		final Consumer<CloseableReference<CloseableImage>> postprocessorConsumer;
		if (postprocessor instanceof RepeatedPostprocessor) {
			postprocessorConsumer = new RepeatedPostprocessorConsumer(basePostprocessorConsumer,
					(RepeatedPostprocessor) postprocessor, context);
		} else {
			postprocessorConsumer = new SingleUsePostprocessorConsumer(basePostprocessorConsumer);
		}
		mNextProducer.produceResults(postprocessorConsumer, context);
	}

	/**
	 * Performs postprocessing and takes care of scheduling.
	 */
	private class PostprocessorConsumer extends
			DelegatingConsumer<CloseableReference<CloseableImage>, CloseableReference<CloseableImage>> {

		private final ProducerListener mListener;
		private final String mRequestId;
		private final Postprocessor mPostprocessor;

		private boolean mIsClosed;

		@Nullable
		private CloseableReference<CloseableImage> mSourceImageRef = null;

		private boolean mIsLast = false;

		private boolean mIsDirty = false;

		private boolean mIsPostProcessingRunning = false;

		public PostprocessorConsumer(Consumer<CloseableReference<CloseableImage>> consumer, ProducerListener listener,
				String requestId, Postprocessor postprocessor, ProducerContext producerContext) {
			super(consumer);
			mListener = listener;
			mRequestId = requestId;
			mPostprocessor = postprocessor;
			producerContext.addCallbacks(new BaseProducerContextCallbacks() {
				@Override
				public void onCancellationRequested() {
					maybeNotifyOnCancellation();
				}
			});
		}

		@Override
		protected void onNewResultImpl(CloseableReference<CloseableImage> newResult, boolean isLast) {
			if (!CloseableReference.isValid(newResult)) {
				// try to propagate if the last result is invalid
				if (isLast) {
					maybeNotifyOnNewResult(null, true);
				}
				// ignore if invalid
				return;
			}
			updateSourceImageRef(newResult, isLast);
		}

		@Override
		protected void onFailureImpl(Throwable t) {
			maybeNotifyOnFailure(t);
		}

		@Override
		protected void onCancellationImpl() {
			maybeNotifyOnCancellation();
		}

		private void updateSourceImageRef(@Nullable CloseableReference<CloseableImage> sourceImageRef, boolean isLast) {
			CloseableReference<CloseableImage> oldSourceImageRef;
			boolean shouldSubmit;
			synchronized (PostprocessorConsumer.this) {
				if (mIsClosed) {
					return;
				}
				oldSourceImageRef = mSourceImageRef;
				mSourceImageRef = CloseableReference.cloneOrNull(sourceImageRef);
				mIsLast = isLast;
				mIsDirty = true;
				shouldSubmit = setRunningIfDirtyAndNotRunning();
			}
			CloseableReference.closeSafely(oldSourceImageRef);
			if (shouldSubmit) {
				submitPostprocessing();
			}
		}

		private void submitPostprocessing() {
			mExecutor.execute(new Runnable() {
				@Override
				public void run() {
					CloseableReference<CloseableImage> closeableImageRef;
					boolean isLast;
					synchronized (PostprocessorConsumer.this) {
						// instead of cloning and closing the reference, we do a
						// more efficient move.
						closeableImageRef = mSourceImageRef;
						isLast = mIsLast;
						mSourceImageRef = null;
						mIsDirty = false;
					}

					if (CloseableReference.isValid(closeableImageRef)) {
						try {
							doPostprocessing(closeableImageRef, isLast);
						} finally {
							CloseableReference.closeSafely(closeableImageRef);
						}
					}
					clearRunningAndStartIfDirty();
				}
			});
		}

		private void clearRunningAndStartIfDirty() {
			boolean shouldExecuteAgain;
			synchronized (PostprocessorConsumer.this) {
				mIsPostProcessingRunning = false;
				shouldExecuteAgain = setRunningIfDirtyAndNotRunning();
			}
			if (shouldExecuteAgain) {
				submitPostprocessing();
			}
		}

		private synchronized boolean setRunningIfDirtyAndNotRunning() {
			if (!mIsClosed && mIsDirty && !mIsPostProcessingRunning && CloseableReference.isValid(mSourceImageRef)) {
				mIsPostProcessingRunning = true;
				return true;
			}
			return false;
		}

		private void doPostprocessing(CloseableReference<CloseableImage> sourceImageRef, boolean isLast) {
			Preconditions.checkArgument(CloseableReference.isValid(sourceImageRef));
			if (!shouldPostprocess(sourceImageRef.get())) {
				maybeNotifyOnNewResult(sourceImageRef, isLast);
				return;
			}
			mListener.onProducerStart(mRequestId, NAME);
			CloseableReference<CloseableImage> destImageRef = null;
			try {
				try {
					destImageRef = postprocessInternal(sourceImageRef.get());
				} catch (Exception e) {
					mListener.onProducerFinishWithFailure(mRequestId, NAME, e,
							getExtraMap(mListener, mRequestId, mPostprocessor));
					maybeNotifyOnFailure(e);
					return;
				}
				mListener.onProducerFinishWithSuccess(mRequestId, NAME,
						getExtraMap(mListener, mRequestId, mPostprocessor));
				maybeNotifyOnNewResult(destImageRef, isLast);
			} finally {
				CloseableReference.closeSafely(destImageRef);
			}
		}

		private Map<String, String> getExtraMap(ProducerListener listener, String requestId, Postprocessor postprocessor) {
			if (!listener.requiresExtraMap(requestId)) {
				return null;
			}
			return ImmutableMap.of(POSTPROCESSOR, postprocessor.getName());
		}

		private boolean shouldPostprocess(CloseableImage sourceImage) {
			return (sourceImage instanceof CloseableStaticBitmap);
		}

		private CloseableReference<CloseableImage> postprocessInternal(CloseableImage sourceImage) {
			Bitmap sourceBitmap = ((CloseableStaticBitmap) sourceImage).getUnderlyingBitmap();
			CloseableReference<Bitmap> bitmapRef = mPostprocessor.process(sourceBitmap, mBitmapFactory);
			try {
				return CloseableReference.<CloseableImage> of(new CloseableStaticBitmap(bitmapRef, sourceImage
						.getQualityInfo()));
			} finally {
				CloseableReference.closeSafely(bitmapRef);
			}
		}

		private void maybeNotifyOnNewResult(CloseableReference<CloseableImage> newRef, boolean isLast) {
			if ((!isLast && !isClosed()) || (isLast && close())) {
				getConsumer().onNewResult(newRef, isLast);
			}
		}

		private void maybeNotifyOnFailure(Throwable throwable) {
			if (close()) {
				getConsumer().onFailure(throwable);
			}
		}

		private void maybeNotifyOnCancellation() {
			if (close()) {
				getConsumer().onCancellation();
			}
		}

		private synchronized boolean isClosed() {
			return mIsClosed;
		}

		private boolean close() {
			CloseableReference<CloseableImage> oldSourceImageRef;
			synchronized (PostprocessorConsumer.this) {
				if (mIsClosed) {
					return false;
				}
				oldSourceImageRef = mSourceImageRef;
				mSourceImageRef = null;
				mIsClosed = true;
			}
			CloseableReference.closeSafely(oldSourceImageRef);
			return true;
		}
	}

	/**
	 * PostprocessorConsumer wrapper that ignores intermediate results.
	 */
	class SingleUsePostprocessorConsumer extends
			DelegatingConsumer<CloseableReference<CloseableImage>, CloseableReference<CloseableImage>> {

		private SingleUsePostprocessorConsumer(PostprocessorConsumer postprocessorConsumer) {
			super(postprocessorConsumer);
		}

		@Override
		protected void onNewResultImpl(final CloseableReference<CloseableImage> newResult, final boolean isLast) {
			// ignore intermediate results
			if (!isLast) {
				return;
			}
			getConsumer().onNewResult(newResult, isLast);
		}
	}

	/**
	 * PostprocessorConsumer wrapper that allows repeated postprocessing.
	 * 
	 * <p>
	 * Reference to the last result received is cloned and kept until the
	 * request is cancelled. In order to allow multiple postprocessing, results
	 * are always propagated as non-final. When {@link #update()} is called, a
	 * new postprocessing of the last received result is requested.
	 * 
	 * <p>
	 * Intermediate results are ignored.
	 */
	class RepeatedPostprocessorConsumer extends
			DelegatingConsumer<CloseableReference<CloseableImage>, CloseableReference<CloseableImage>> implements
			RepeatedPostprocessorRunner {

		private boolean mIsClosed = false;

		@Nullable
		private CloseableReference<CloseableImage> mSourceImageRef = null;

		private RepeatedPostprocessorConsumer(PostprocessorConsumer postprocessorConsumer,
				RepeatedPostprocessor repeatedPostprocessor, ProducerContext context) {
			super(postprocessorConsumer);
			repeatedPostprocessor.setCallback(RepeatedPostprocessorConsumer.this);
			context.addCallbacks(new BaseProducerContextCallbacks() {
				@Override
				public void onCancellationRequested() {
					if (close()) {
						getConsumer().onCancellation();
					}
				}
			});
		}

		@Override
		protected void onNewResultImpl(CloseableReference<CloseableImage> newResult, boolean isLast) {
			// ignore intermediate results
			if (!isLast) {
				return;
			}
			setSourceImageRef(newResult);
			updateInternal();
		}

		@Override
		protected void onFailureImpl(Throwable throwable) {
			if (close()) {
				getConsumer().onFailure(throwable);
			}
		}

		@Override
		protected void onCancellationImpl() {
			if (close()) {
				getConsumer().onCancellation();
			}
		}

		@Override
		public synchronized void update() {
			updateInternal();
		}

		private void updateInternal() {
			CloseableReference<CloseableImage> sourceImageRef;
			synchronized (RepeatedPostprocessorConsumer.this) {
				if (mIsClosed) {
					return;
				}
				sourceImageRef = CloseableReference.cloneOrNull(mSourceImageRef);
			}
			try {
				getConsumer().onNewResult(sourceImageRef, false /* isLast */);
			} finally {
				CloseableReference.closeSafely(sourceImageRef);
			}
		}

		private void setSourceImageRef(CloseableReference<CloseableImage> sourceImageRef) {
			CloseableReference<CloseableImage> oldSourceImageRef;
			synchronized (RepeatedPostprocessorConsumer.this) {
				if (mIsClosed) {
					return;
				}
				oldSourceImageRef = mSourceImageRef;
				mSourceImageRef = CloseableReference.cloneOrNull(sourceImageRef);
			}
			CloseableReference.closeSafely(oldSourceImageRef);
		}

		private boolean close() {
			CloseableReference<CloseableImage> oldSourceImageRef;
			synchronized (RepeatedPostprocessorConsumer.this) {
				if (mIsClosed) {
					return false;
				}
				oldSourceImageRef = mSourceImageRef;
				mSourceImageRef = null;
				mIsClosed = true;
			}
			CloseableReference.closeSafely(oldSourceImageRef);
			return true;
		}
	}
}
