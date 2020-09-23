/*-
 * #%L
 * rapidoid-commons
 * %%
 * Copyright (C) 2014 - 2018 Nikolche Mihajlovski and contributors
 * %%
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
 * #L%
 */

package org.rapidoid.io;

import org.rapidoid.RapidoidThing;
import org.rapidoid.annotation.Authors;
import org.rapidoid.annotation.Since;
import org.rapidoid.collection.Coll;
import org.rapidoid.env.Env;
import org.rapidoid.log.Log;
import org.rapidoid.u.U;
import org.rapidoid.util.Msc;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Authors("Nikolche Mihajlovski")
@Since("4.1.0")
public class Res extends RapidoidThing {

	public static volatile Pattern REGEX_INVALID_FILENAME = Pattern.compile("(?:[*?'\"<>|\\x00-\\x1F]|\\.\\.)");

//	private static final ConcurrentMap<ResKey, Res> FILES = Coll.concurrentMap();

	public static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(1);

	private static final String[] DEFAULT_LOCATIONS = {""};

	private final String name;

	private final String[] possibleLocations;

	private volatile byte[] bytes;

	private volatile long lastUpdatedOn;

	private volatile long lastModified;

	private volatile String content;

	private volatile boolean trackingChanges;

	private volatile String cachedFileName;

	private volatile Object attachment;

	private volatile boolean hidden;

	private final Map<String, Runnable> changeListeners = Coll.synchronizedMap();

  ///// the following declarations are used for cache implementation
  private static volatile int placeInLine = -1;
  private static AtomicInteger circularLinePos = new AtomicInteger(0);
  private static ReadWriteLock circularLock = new ReentrantReadWriteLock();
  private static AtomicInteger cachedFilesCount = new AtomicInteger(0);
  private static int EXPECTED_ENTRIES = 256;
  private static float LOAD_FACTOR = 0.5F;
  private static int CONCURRENCY_LEVEL = 4;

  private static volatile int MAX_CACHE_SIZE = 67108864; // 64MB
  private static volatile int MIN_CACHE_SIZE = 4194304; //4 MB
  private static volatile int MAX_FILE_IN_CACHE = 1048576; // 1MB
  private static LongAdder sizeOfCachedFiles = new LongAdder();
  private static volatile Res[] circularLineQueue = new Res[4096];

  private static final ConcurrentHashMap<String, Res> FILES
                    = new ConcurrentHashMap<String, Res>(
                    EXPECTED_ENTRIES,
                    LOAD_FACTOR,
                    CONCURRENCY_LEVEL);

	private Res(String name, String... possibleLocations) {
		this.name = name;
		this.possibleLocations = possibleLocations;

		validateFilename(name);
	}

	private static void validateFilename(String filename) {
        // fix for exposing resource name
        U.must(!filename.endsWith("..html"), "Invalid Request", "");
		U.must(!Res.REGEX_INVALID_FILENAME.matcher(filename).find(), "Invalid resource name: %s", filename);
	}

	public static Res from(File file, String... possibleLocations) {
		U.must(!file.isAbsolute() || U.isEmpty(possibleLocations), "Cannot specify locations for an absolute filename!");
		return file.isAbsolute() ? absolute(file) : relative(file.getPath(), possibleLocations);
	}

	public static Res from(String filename, String... possibleLocations) {
		return from(new File(filename), possibleLocations);
	}

	private static Res absolute(File file) {
		return create(file.getAbsolutePath());
	}

	private static Res relative(String filename, String... possibleLocations) {
		File file = new File(filename);

		if (file.isAbsolute()) {
			return absolute(file);
		}

		if (U.isEmpty(possibleLocations)) {
			possibleLocations = DEFAULT_LOCATIONS;
		}

		U.must(!U.isEmpty(filename), "Resource filename must be specified!");
		U.must(!file.isAbsolute(), "Expected relative filename!");

		String root = Env.root();
		if (U.notEmpty(Env.root())) {
			String[] loc = new String[possibleLocations.length * 2];

			for (int i = 0; i < possibleLocations.length; i++) {
				loc[2 * i] = Msc.path(root, possibleLocations[i]);
				loc[2 * i + 1] = possibleLocations[i];
			}

			possibleLocations = loc;
		}

		return create(filename, possibleLocations);
	}

  // IMPROVED Scalability: Added cache implementation 
	private static Res create(String filename, String... possibleLocations) {
		for (int i = 0; i < possibleLocations.length; i++) {
			possibleLocations[i] = Msc.refinePath(possibleLocations[i]);
		}

    if(0 <possibleLocations.length) {
        for(int i = 0, len = possibleLocations.length; i<len; i++) {
            Res found = FILES.get(Msc.path(possibleLocations[i], filename));
            if(found != null) {
                               updatePlaceInLine( found );
                               return found;
            }
        }
        }else {
                Res found = FILES.get(filename);
                if(found != null) {
                        updatePlaceInLine(found);
                        return found;
                }
        }

//		ResKey key = new ResKey(filename, possibleLocations);

		Res cachedFile = FILES.get(key);

		if (cachedFile == null) {
			cachedFile = new Res(filename, possibleLocations);

    FILES.putIfAbsent(filename, cachedFile);
    
			//if (FILES.size() < 1000) {
				// FIXME use real cache with proper expiration
				//FILES.putIfAbsent(key, cachedFile);
			//}
		}

		return cachedFile;
	}

  /*
   * This method updates the 'Res' object into the circular buffer
   * If there is an existing entry, it will invalidate it and updates
   * with the current one.
   */ 
  private static void updatePlaceInLine(Res res) {
    Lock reader = circularLock.readLock();
    reader.lock();
    try {
          do {
                  int newPos    = circularLinePos.getAndIncrement();
                  newPos        = newPos % circularLineQueue.length;
                  Res oldObject = circularLineQueue[newPos];
                  if(oldObject != null) {
                     long cacheSizeSum = sizeOfCachedFiles.sum();
                     boolean cacheSizeTooSmall = cacheSizeSum < MIN_CACHE_SIZE;
                     boolean cacheProjectionSmallEnough = cacheSizeSum * circularLineQueue.length <= MAX_FILE_IN_CACHE * cachedFilesCount.getAndIncrement();
                     if(cacheSizeTooSmall || cacheProjectionSmallEnough) {
                             reader.unlock();
                             resizeCircularArray();
                             reader.lock();
                             continue;
                     }
                     oldObject.invalidate();
                  }
                  circularLineQueue[newPos] = res;
                  if(placeInLine != -1) circularLineQueue[placeInLine] = null;
                  placeInLine = newPos;
          }while(false);
        }finally {
              reader.unlock();
        }
  }

  /*
   * This method will resize the circular buffer
   *
   */
  private static void resizeCircularArray() {
    Lock writer = circularLock.writeLock();
    writer.lock();
    try {
          int oldLength = circularLineQueue.length;
          Res[] newCircularLineQueue = new Res[oldLength >>1 + oldLength];

          int currentPos = circularLinePos.get(), toPos = 0;
          currentPos = currentPos % oldLength;
          for(int from = currentPos; from != currentPos; ) {
              Res oldCachedRes = circularLineQueue[from];
              if( oldCachedRes != null) {
                    newCircularLineQueue[toPos] = oldCachedRes;
                    oldCachedRes.setPlaceInLine(toPos);
                    ++toPos;
              }
              if(++from == oldLength) from = 0;
          }
          
          circularLinePos.set(toPos);
          circularLineQueue = newCircularLineQueue;
          
          }finally {
                  writer.unlock();
          }
  }

	public synchronized byte[] getBytes() {
		loadResource();

		mustExist();
		return bytes;
	}

	public byte[] getBytesOrNull() {
		loadResource();
		return bytes;
	}

	protected void loadResource() {
		// micro-caching the file content, expires after 500ms
		if (U.timedOut(lastUpdatedOn, 500)) {
			boolean hasChanged;

			synchronized (this) {

				byte[] old = bytes;
				byte[] foundRes = null;

				if (possibleLocations.length == 0) {
					Log.trace("Trying to load the resource", "name", name);

					byte[] res = load(name);
					if (res != null) {
						Log.debug("Loaded the resource", "name", name);
						foundRes = res;
						this.cachedFileName = name;
					}

				} else {
					for (String location : possibleLocations) {
						String filename = Msc.path(location, name);

						Log.trace("Trying to load the resource", "name", name, "location", location, "filename", filename);

						byte[] res = load(filename);
						if (res != null) {
							Log.debug("Loaded the resource", "name", name, "file", filename);
							foundRes = res;
							this.cachedFileName = filename;
							break;
						}
					}
				}

				if (foundRes == null) {
					this.cachedFileName = null;
				}

				this.bytes = foundRes;
				hasChanged = !U.eq(old, bytes) && (old == null || bytes == null || !Arrays.equals(old, bytes));
				lastUpdatedOn = U.time();

				if (hasChanged) {
					content = null;
					attachment = null;
				}
			}

			if (hasChanged) {
				notifyChangeListeners();
			}
		}
	}

	protected byte[] load(String filename) {
		File file = IO.file(filename);

		if (file.exists()) {

			if (!file.isFile() || file.isDirectory()) {
				return null;
			}

			// a normal file on the file system
			Log.trace("Resource file exists", "name", name, "file", file);

			long lastModif;
			try {
				lastModif = Files.getLastModifiedTime(file.toPath()).to(TimeUnit.MILLISECONDS);

			} catch (IOException e) {
				// maybe it doesn't exist anymore
				lastModif = U.time();
			}

			if (lastModif > this.lastModified || !filename.equals(cachedFileName)) {
				Log.debug("Loading resource file", "name", name, "file", file);
				this.lastModified = file.lastModified();
				this.hidden = file.isHidden();
				return IO.loadBytes(filename);

			} else {
				Log.trace("Resource file not modified", "name", name, "file", file);
				return bytes;
			}

		} else {
			// it might not exist or it might be on the classpath or compressed in a JAR
			Log.trace("Trying to load classpath resource", "name", name, "file", file);
			this.hidden = false;
			return IO.loadBytes(filename);
		}
	}

	public synchronized String getContent() {
		mustExist();

		if (content == null) {
			byte[] b = getBytes();
			content = b != null ? new String(b) : null;
		}

		return content;
	}

	public boolean exists() {
		loadResource();
		return bytes != null;
	}

	public String getName() {
		return name;
	}

	@Override
	public String toString() {
		return "Res(" + name + ")";
	}

	public synchronized Reader getReader() {
		mustExist();
		return new StringReader(getContent());
	}

	public Res mustExist() {
		// IMPROVED 🌥️ error presentation: Removed the full path of the file and use only file name
    U.must(exists(), "The file '%s' doesn't exist!", extractFileNameFromPath(name));
		return this;
	}

	public Res onChange(String name, Runnable listener) {
		changeListeners.put(name, listener);
		return this;
	}

	public Res removeChangeListener(String name) {
		changeListeners.remove(name);
		return this;
	}

	private void notifyChangeListeners() {
		if (!changeListeners.isEmpty()) {
			Log.info("Resource has changed, reloading...", "name", name);
		}

		for (Runnable listener : changeListeners.values()) {
			try {
				listener.run();
			} catch (Throwable e) {
				Log.error("Error while processing resource changes!", e);
			}
		}
	}

	public synchronized Res trackChanges() {
		if (!trackingChanges) {
			this.trackingChanges = true;
			// loading the resource causes the resource to check for changes
			EXECUTOR.scheduleWithFixedDelay(this::loadResource, 0, 300, TimeUnit.MILLISECONDS);
		}

		return this;
	}

	@SuppressWarnings("unchecked")
	public <T> T attachment() {
		return exists() ? (T) attachment : null;
	}

	public void attach(Object attachment) {
		this.attachment = attachment;
	}

	public String getCachedFileName() {
		return cachedFileName;
	}

	public static synchronized void reset() {
		for (Res res : FILES.values()) {
			res.invalidate();
		}
		FILES.clear();
	}

	public void invalidate() {
		lastUpdatedOn = 0;
	}

	public boolean isHidden() {
		return hidden;
	}
  
  	/**
	 * remove the complete path from the file and
	 * returns only file name at the time of displaying
   * it on the client side
	 * @param name
	 * @return
	 */
	private String extractFileNameFromPath(String name) {
		return new File(name).getName();
	}

}
