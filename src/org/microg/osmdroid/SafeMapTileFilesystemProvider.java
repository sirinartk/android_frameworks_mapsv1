package org.microg.osmdroid;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import org.osmdroid.tileprovider.ExpirableBitmapDrawable;
import org.osmdroid.tileprovider.IRegisterReceiver;
import org.osmdroid.tileprovider.MapTile;
import org.osmdroid.tileprovider.MapTileRequestState;
import org.osmdroid.tileprovider.modules.MapTileFileStorageProviderBase;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase.LowMemoryException;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements a file system cache and provides cached tiles. This functions as a tile provider by
 * serving cached tiles for the supplied tile source.
 *
 * This modified version checks if the default cache is accessible (by checking for the
 * WRITE_EXTERNAL_STORAGE-permission) and uses an app-specific cache if not.
 *
 * @author Marc Kurtz
 * @author Nicolas Gramlich
 *
 */
public class SafeMapTileFilesystemProvider extends MapTileFileStorageProviderBase {

	// ===========================================================
	// Constants
	// ===========================================================

	private static final Logger logger = LoggerFactory.getLogger(SafeMapTileFilesystemProvider.class);

	// ===========================================================
	// Fields
	// ===========================================================

	private final long mMaximumCachedFileAge;

	private final AtomicReference<ITileSource> mTileSource = new AtomicReference<ITileSource>();

	private final File safeTilePathBase;

	// ===========================================================
	// Constructors
	// ===========================================================

	public SafeMapTileFilesystemProvider(Context context, final IRegisterReceiver pRegisterReceiver) {
		this(context, pRegisterReceiver, TileSourceFactory.DEFAULT_TILE_SOURCE);
	}

	public SafeMapTileFilesystemProvider(Context context, final IRegisterReceiver pRegisterReceiver,
	                                     final ITileSource aTileSource) {
		this(context, pRegisterReceiver, aTileSource, DEFAULT_MAXIMUM_CACHED_FILE_AGE);
	}

	public SafeMapTileFilesystemProvider(Context context, final IRegisterReceiver pRegisterReceiver,
	                                     final ITileSource pTileSource, final long pMaximumCachedFileAge) {
		this(context, pRegisterReceiver, pTileSource, pMaximumCachedFileAge,
				NUMBER_OF_TILE_FILESYSTEM_THREADS,
				TILE_FILESYSTEM_MAXIMUM_QUEUE_SIZE);
	}

	/**
	 * Provides a file system based cache tile provider. Other providers can register and store data
	 * in the cache.
	 *
	 * @param pRegisterReceiver
	 */
	public SafeMapTileFilesystemProvider(Context context, final IRegisterReceiver pRegisterReceiver,
	                                     final ITileSource pTileSource, final long pMaximumCachedFileAge, int pThreadPoolSize,
	                                     int pPendingQueueSize) {
		super(pRegisterReceiver, pThreadPoolSize, pPendingQueueSize);
		if (context.checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
			safeTilePathBase = TILE_PATH_BASE;
		} else {
			safeTilePathBase = new File(context.getExternalCacheDir(), "tiles");
		}
		setTileSource(pTileSource);

		mMaximumCachedFileAge = pMaximumCachedFileAge;
	}
	// ===========================================================
	// Getter & Setter
	// ===========================================================

	// ===========================================================
	// Methods from SuperClass/Interfaces
	// ===========================================================

	@Override
	public boolean getUsesDataConnection() {
		return false;
	}

	@Override
	protected String getName() {
		return "File System Cache Provider";
	}

	@Override
	protected String getThreadGroupName() {
		return "filesystem";
	}

	@Override
	protected Runnable getTileLoader() {
		return new TileLoader();
	}

	@Override
	public int getMinimumZoomLevel() {
		ITileSource tileSource = mTileSource.get();
		return tileSource != null ? tileSource.getMinimumZoomLevel() : MINIMUM_ZOOMLEVEL;
	}

	@Override
	public int getMaximumZoomLevel() {
		ITileSource tileSource = mTileSource.get();
		return tileSource != null ? tileSource.getMaximumZoomLevel() : MAXIMUM_ZOOMLEVEL;
	}

	@Override
	public void setTileSource(final ITileSource pTileSource) {
		mTileSource.set(pTileSource);
	}

	// ===========================================================
	// Inner and Anonymous Classes
	// ===========================================================

	protected class TileLoader extends MapTileModuleProviderBase.TileLoader {

		@Override
		public Drawable loadTile(final MapTileRequestState pState) throws CantContinueException {

			ITileSource tileSource = mTileSource.get();
			if (tileSource == null) {
				return null;
			}

			final MapTile tile = pState.getMapTile();

			// if there's no sdcard then don't do anything
			if (!getSdCardAvailable()) {
				if (DEBUGMODE) {
					logger.debug("No sdcard - do nothing for tile: " + tile);
				}
				return null;
			}

			// Check the tile source to see if its file is available and if so, then render the
			// drawable and return the tile
			final File file = new File(safeTilePathBase,
					tileSource.getTileRelativeFilenameString(tile) + TILE_PATH_EXTENSION);
			if (file.exists()) {

				try {
					final Drawable drawable = tileSource.getDrawable(file.getPath());

					// Check to see if file has expired
					final long now = System.currentTimeMillis();
					final long lastModified = file.lastModified();
					final boolean fileExpired = lastModified < now - mMaximumCachedFileAge;

					if (fileExpired) {
						if (DEBUGMODE) {
							logger.debug("Tile expired: " + tile);
						}
						drawable.setState(new int[] {ExpirableBitmapDrawable.EXPIRED });
					}

					return drawable;
				} catch (final LowMemoryException e) {
					// low memory so empty the queue
					logger.warn("LowMemoryException downloading MapTile: " + tile + " : " + e);
					throw new CantContinueException(e);
				}
			}

			// If we get here then there is no file in the file cache
			return null;
		}
	}
}
