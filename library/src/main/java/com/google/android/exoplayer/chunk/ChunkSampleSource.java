/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.chunk;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.DecoderInputBuffer;
import com.google.android.exoplayer.Format;
import com.google.android.exoplayer.FormatHolder;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.TrackGroup;
import com.google.android.exoplayer.TrackGroupArray;
import com.google.android.exoplayer.TrackSelection;
import com.google.android.exoplayer.TrackStream;
import com.google.android.exoplayer.chunk.ChunkSampleSourceEventListener.EventDispatcher;
import com.google.android.exoplayer.extractor.DefaultTrackOutput;
import com.google.android.exoplayer.upstream.Loader;
import com.google.android.exoplayer.upstream.Loader.Loadable;
import com.google.android.exoplayer.util.Assertions;

import android.os.Handler;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * A {@link SampleSource} that loads media in {@link Chunk}s, which are themselves obtained from a
 * {@link ChunkSource}.
 */
public class ChunkSampleSource implements TrackStream, Loader.Callback {

  /**
   * The default minimum number of times to retry loading data prior to failing.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3;

  private final Loader loader;
  private final ChunkSource chunkSource;
  private final LinkedList<BaseMediaChunk> mediaChunks;
  private final List<BaseMediaChunk> readOnlyMediaChunks;
  private final DefaultTrackOutput sampleQueue;
  private final int bufferSizeContribution;
  private final ChunkHolder nextChunkHolder;
  private final EventDispatcher eventDispatcher;
  private final LoadControl loadControl;

  private boolean notifyReset;
  private long lastPreferredQueueSizeEvaluationTimeMs;
  private Format downstreamFormat;

  private TrackGroupArray trackGroups;
  private boolean trackEnabled;

  private long downstreamPositionUs;
  private long lastSeekPositionUs;
  private long pendingResetPositionUs;

  private Chunk currentLoadable;
  private long currentLoadStartTimeMs;
  private boolean loadingFinished;

  /**
   * @param chunkSource A {@link ChunkSource} from which chunks to load are obtained.
   * @param loadControl Controls when the source is permitted to load data.
   * @param bufferSizeContribution The contribution of this source to the media buffer, in bytes.
   */
  public ChunkSampleSource(ChunkSource chunkSource, LoadControl loadControl,
      int bufferSizeContribution) {
    this(chunkSource, loadControl, bufferSizeContribution, null, null, 0);
  }

  /**
   * @param chunkSource A {@link ChunkSource} from which chunks to load are obtained.
   * @param loadControl Controls when the source is permitted to load data.
   * @param bufferSizeContribution The contribution of this source to the media buffer, in bytes.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param eventSourceId An identifier that gets passed to {@code eventListener} methods.
   */
  public ChunkSampleSource(ChunkSource chunkSource, LoadControl loadControl,
      int bufferSizeContribution, Handler eventHandler,
      ChunkSampleSourceEventListener eventListener, int eventSourceId) {
    this(chunkSource, loadControl, bufferSizeContribution, eventHandler, eventListener,
        eventSourceId, DEFAULT_MIN_LOADABLE_RETRY_COUNT);
  }

  /**
   * @param chunkSource A {@link ChunkSource} from which chunks to load are obtained.
   * @param loadControl Controls when the source is permitted to load data.
   * @param bufferSizeContribution The contribution of this source to the media buffer, in bytes.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param eventSourceId An identifier that gets passed to {@code eventListener} methods.
   * @param minLoadableRetryCount The minimum number of times that the source should retry a load
   *     before propagating an error.
   */
  public ChunkSampleSource(ChunkSource chunkSource, LoadControl loadControl,
      int bufferSizeContribution, Handler eventHandler,
      ChunkSampleSourceEventListener eventListener, int eventSourceId, int minLoadableRetryCount) {
    this.chunkSource = chunkSource;
    this.loadControl = loadControl;
    this.bufferSizeContribution = bufferSizeContribution;
    loader = new Loader("Loader:ChunkSampleSource", minLoadableRetryCount);
    eventDispatcher = new EventDispatcher(eventHandler, eventListener, eventSourceId);
    nextChunkHolder = new ChunkHolder();
    mediaChunks = new LinkedList<>();
    readOnlyMediaChunks = Collections.unmodifiableList(mediaChunks);
    sampleQueue = new DefaultTrackOutput(loadControl.getAllocator());
    pendingResetPositionUs = C.UNSET_TIME_US;
  }

  // SampleSource implementation.

  public void prepare() {
    TrackGroup tracks = chunkSource.getTracks();
    if (tracks != null) {
      trackGroups = new TrackGroupArray(tracks);
    } else {
      trackGroups = new TrackGroupArray();
    }
  }

  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  public TrackStream[] selectTracks(List<TrackStream> oldStreams,
      List<TrackSelection> newSelections, long positionUs) {
    Assertions.checkState(oldStreams.size() <= 1);
    Assertions.checkState(newSelections.size() <= 1);
    boolean trackWasEnabled = trackEnabled;
    // Unselect old tracks.
    if (!oldStreams.isEmpty()) {
      Assertions.checkState(trackEnabled);
      trackEnabled = false;
      chunkSource.disable();
    }
    // Select new tracks.
    TrackStream[] newStreams = new TrackStream[newSelections.size()];
    if (!newSelections.isEmpty()) {
      Assertions.checkState(!trackEnabled);
      trackEnabled = true;
      chunkSource.enable(newSelections.get(0).getTracks());
      newStreams[0] = this;
    }
    // Cancel or start requests as necessary.
    if (!trackEnabled) {
      if (trackWasEnabled) {
        loadControl.unregister(this);
      }
      if (loader.isLoading()) {
        loader.cancelLoading();
      } else {
        clearState();
        loadControl.trimAllocator();
      }
    } else {
      if (!trackWasEnabled) {
        loadControl.register(this, bufferSizeContribution);
      }
      downstreamFormat = null;
      sampleQueue.needDownstreamFormat();
      downstreamPositionUs = positionUs;
      lastSeekPositionUs = positionUs;
      notifyReset = false;
      restartFrom(positionUs);
    }
    return newStreams;
  }

  public void continueBuffering(long positionUs) {
    downstreamPositionUs = positionUs;
    if (!loader.isLoading()) {
      maybeStartLoading();
    }
  }

  public long readReset() {
    if (notifyReset) {
      notifyReset = false;
      return lastSeekPositionUs;
    }
    return C.UNSET_TIME_US;
  }

  public long getBufferedPositionUs() {
    if (loadingFinished) {
      return C.END_OF_SOURCE_US;
    } else if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      long bufferedPositionUs = downstreamPositionUs;
      BaseMediaChunk lastMediaChunk = mediaChunks.getLast();
      BaseMediaChunk lastCompletedMediaChunk = lastMediaChunk != currentLoadable ? lastMediaChunk
          : mediaChunks.size() > 1 ? mediaChunks.get(mediaChunks.size() - 2) : null;
      if (lastCompletedMediaChunk != null) {
        bufferedPositionUs = Math.max(bufferedPositionUs, lastCompletedMediaChunk.endTimeUs);
      }
      return Math.max(bufferedPositionUs, sampleQueue.getLargestQueuedTimestampUs());
    }
  }

  public void seekToUs(long positionUs) {
    downstreamPositionUs = positionUs;
    lastSeekPositionUs = positionUs;
    // If we're not pending a reset, see if we can seek within the sample queue.
    boolean seekInsideBuffer = !isPendingReset() && sampleQueue.skipToKeyframeBefore(positionUs);
    if (seekInsideBuffer) {
      // We succeeded. All we need to do is discard any chunks that we've moved past.
      while (mediaChunks.size() > 1
          && mediaChunks.get(1).getFirstSampleIndex() <= sampleQueue.getReadIndex()) {
        mediaChunks.removeFirst();
      }
    } else {
      // We failed, and need to restart.
      restartFrom(positionUs);
    }
    // Either way, we need to send a discontinuity to the downstream components.
    notifyReset = true;
  }

  public void release() {
    if (trackEnabled) {
      loadControl.unregister(this);
      trackEnabled = false;
    }
    loader.release();
  }

  // TrackStream implementation.

  @Override
  public boolean isReady() {
    return loadingFinished || (!isPendingReset() && !sampleQueue.isEmpty());
  }

  @Override
  public void maybeThrowError() throws IOException {
    loader.maybeThrowError();
    chunkSource.maybeThrowError();
  }

  @Override
  public int readData(FormatHolder formatHolder, DecoderInputBuffer buffer) {
    if (notifyReset || isPendingReset()) {
      return NOTHING_READ;
    }

    while (mediaChunks.size() > 1
        && mediaChunks.get(1).getFirstSampleIndex() <= sampleQueue.getReadIndex()) {
      mediaChunks.removeFirst();
    }
    BaseMediaChunk currentChunk = mediaChunks.getFirst();
    Format currentFormat = currentChunk.format;
    if (downstreamFormat == null || !downstreamFormat.equals(currentFormat)) {
      eventDispatcher.downstreamFormatChanged(currentFormat, currentChunk.trigger,
          currentChunk.startTimeUs);
      downstreamFormat = currentFormat;
    }

    int result = sampleQueue.readData(formatHolder, buffer, loadingFinished, lastSeekPositionUs);
    if (result == FORMAT_READ) {
      formatHolder.drmInitData = currentChunk.getDrmInitData();
    }
    return result;
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadCompleted(Loadable loadable) {
    long now = SystemClock.elapsedRealtime();
    long loadDurationMs = now - currentLoadStartTimeMs;
    chunkSource.onChunkLoadCompleted(currentLoadable);
    if (isMediaChunk(currentLoadable)) {
      BaseMediaChunk mediaChunk = (BaseMediaChunk) currentLoadable;
      eventDispatcher.loadCompleted(currentLoadable.bytesLoaded(), mediaChunk.type,
          mediaChunk.trigger, mediaChunk.format, mediaChunk.startTimeUs, mediaChunk.endTimeUs, now,
          loadDurationMs);
    } else {
      eventDispatcher.loadCompleted(currentLoadable.bytesLoaded(), currentLoadable.type,
          currentLoadable.trigger, currentLoadable.format, -1, -1, now, loadDurationMs);
    }
    clearCurrentLoadable();
    maybeStartLoading();
  }

  @Override
  public void onLoadCanceled(Loadable loadable) {
    eventDispatcher.loadCanceled(currentLoadable.bytesLoaded());
    if (trackEnabled) {
      restartFrom(pendingResetPositionUs);
    } else {
      clearState();
      loadControl.trimAllocator();
    }
  }

  @Override
  public int onLoadError(Loadable loadable, IOException e) {
    long bytesLoaded = currentLoadable.bytesLoaded();
    boolean isMediaChunk = isMediaChunk(currentLoadable);
    boolean cancelable = !isMediaChunk || bytesLoaded == 0 || mediaChunks.size() > 1;
    if (chunkSource.onChunkLoadError(currentLoadable, cancelable, e)) {
      if (isMediaChunk) {
        BaseMediaChunk removed = mediaChunks.removeLast();
        Assertions.checkState(removed == currentLoadable);
        sampleQueue.discardUpstreamSamples(removed.getFirstSampleIndex());
        if (mediaChunks.isEmpty()) {
          pendingResetPositionUs = lastSeekPositionUs;
        }
      }
      clearCurrentLoadable();
      eventDispatcher.loadError(e);
      eventDispatcher.loadCanceled(bytesLoaded);
      maybeStartLoading();
      return Loader.DONT_RETRY;
    } else {
      eventDispatcher.loadError(e);
      return Loader.RETRY;
    }
  }

  // Internal methods.

  private void restartFrom(long positionUs) {
    pendingResetPositionUs = positionUs;
    loadingFinished = false;
    if (loader.isLoading()) {
      loader.cancelLoading();
    } else {
      clearState();
      maybeStartLoading();
    }
  }

  private void clearState() {
    sampleQueue.clear();
    mediaChunks.clear();
    clearCurrentLoadable();
  }

  private void clearCurrentLoadable() {
    currentLoadable = null;
  }

  private void maybeStartLoading() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastPreferredQueueSizeEvaluationTimeMs > 5000) {
      int queueSize = chunkSource.getPreferredQueueSize(downstreamPositionUs, readOnlyMediaChunks);
      // Never discard the first chunk.
      discardUpstreamMediaChunks(Math.max(1, queueSize));
      lastPreferredQueueSizeEvaluationTimeMs = now;
    }

    boolean isNext = loadControl.update(this, downstreamPositionUs, getNextLoadPositionUs(), false);
    if (!isNext) {
      return;
    }

    chunkSource.getNextChunk(mediaChunks.isEmpty() ? null : mediaChunks.getLast(),
        pendingResetPositionUs != C.UNSET_TIME_US ? pendingResetPositionUs : downstreamPositionUs,
        nextChunkHolder);
    boolean endOfStream = nextChunkHolder.endOfStream;
    Chunk nextLoadable = nextChunkHolder.chunk;
    nextChunkHolder.clear();

    if (endOfStream) {
      loadingFinished = true;
      loadControl.update(this, downstreamPositionUs, C.UNSET_TIME_US, false);
      return;
    }

    if (nextLoadable == null) {
      return;
    }

    currentLoadStartTimeMs = now;
    currentLoadable = nextLoadable;
    if (isMediaChunk(currentLoadable)) {
      pendingResetPositionUs = C.UNSET_TIME_US;
      BaseMediaChunk mediaChunk = (BaseMediaChunk) currentLoadable;
      mediaChunk.init(sampleQueue);
      mediaChunks.add(mediaChunk);
      eventDispatcher.loadStarted(mediaChunk.dataSpec.length, mediaChunk.type, mediaChunk.trigger,
          mediaChunk.format, mediaChunk.startTimeUs, mediaChunk.endTimeUs);
    } else {
      eventDispatcher.loadStarted(currentLoadable.dataSpec.length, currentLoadable.type,
          currentLoadable.trigger, currentLoadable.format, -1, -1);
    }
    loader.startLoading(currentLoadable, this);
    // Update the load control again to indicate that we're now loading.
    loadControl.update(this, downstreamPositionUs, getNextLoadPositionUs(), true);
  }

  /**
   * Gets the next load time, assuming that the next load starts where the previous chunk ended (or
   * from the pending reset time, if there is one).
   */
  private long getNextLoadPositionUs() {
    if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      return loadingFinished ? C.UNSET_TIME_US : mediaChunks.getLast().endTimeUs;
    }
  }

  private boolean isMediaChunk(Chunk chunk) {
    return chunk instanceof BaseMediaChunk;
  }

  private boolean isPendingReset() {
    return pendingResetPositionUs != C.UNSET_TIME_US;
  }

  /**
   * Discard upstream media chunks until the queue length is equal to the length specified.
   *
   * @param queueLength The desired length of the queue.
   * @return True if chunks were discarded. False otherwise.
   */
  private boolean discardUpstreamMediaChunks(int queueLength) {
    if (mediaChunks.size() <= queueLength) {
      return false;
    }
    long startTimeUs = 0;
    long endTimeUs = mediaChunks.getLast().endTimeUs;

    BaseMediaChunk removed = null;
    while (mediaChunks.size() > queueLength) {
      removed = mediaChunks.removeLast();
      startTimeUs = removed.startTimeUs;
      loadingFinished = false;
    }
    sampleQueue.discardUpstreamSamples(removed.getFirstSampleIndex());
    eventDispatcher.upstreamDiscarded(startTimeUs, endTimeUs);
    return true;
  }

}
