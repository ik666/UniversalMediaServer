/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is a free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; version 2 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package net.pms.store;

import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.pms.database.MediaDatabase;
import net.pms.database.MediaTableFiles;
import net.pms.database.MediaTableTVSeries;
import net.pms.database.MediaTableThumbnails;
import net.pms.dlna.DLNAThumbnail;
import net.pms.dlna.DLNAThumbnailInputStream;

public class ThumbnailStore {

	private static final Map<Long, WeakReference<DLNAThumbnail>> STORE = new ConcurrentHashMap<>();

	private static AtomicLong tempId = new AtomicLong(Long.MAX_VALUE);

	private ThumbnailStore() {
		//should not be instantiated
	}

	public static Long getId(DLNAThumbnail thumbnail) {
		if (thumbnail == null) {
			return null;
		}
		Connection connection = null;
		Long id = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			if (connection != null) {
				id = MediaTableThumbnails.setThumbnail(connection, thumbnail);
				if (id != null) {
					STORE.put(id, new WeakReference<>(thumbnail));
				}
			}
		} finally {
			MediaDatabase.close(connection);
		}
		return id;
	}

	public static Long getId(DLNAThumbnail thumbnail, Long fileId, ThumbnailSource thumbnailSource) {
		Long id = getId(thumbnail);
		if (id != null && fileId != null) {
			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();
				if (connection != null) {
					MediaTableFiles.updateThumbnailId(connection, fileId, id, thumbnailSource.toString());
				}
			} finally {
				MediaDatabase.close(connection);
			}
		}
		return id;
	}

	public static Long getIdForTvSeries(DLNAThumbnail thumbnail, long tvSeriesId, ThumbnailSource thumbnailSource) {
		Long id = getId(thumbnail);
		if (id != null) {
			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();
				if (connection != null) {
					MediaTableTVSeries.updateThumbnailId(connection, tvSeriesId, id, thumbnailSource.toString());
				}
			} finally {
				MediaDatabase.close(connection);
			}
		}
		return id;
	}

	public static Long getTempId(DLNAThumbnail thumbnail) {
		if (thumbnail == null) {
			return null;
		}
		//resume/temp thumbnail
		Long id = tempId.decrementAndGet();
		STORE.put(id, new WeakReference<>(thumbnail));
		return id;
	}

	public static DLNAThumbnail getThumbnail(Long id) {
		if (id == null) {
			return null;
		}
		WeakReference<DLNAThumbnail> wr = STORE.get(id);
		if (wr != null) {
			return wr.get();
		}
		Connection connection = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			if (connection != null) {
				DLNAThumbnail thumbnail = MediaTableThumbnails.getThumbnail(connection, id);
				if (thumbnail != null) {
					STORE.put(id, new WeakReference<>(thumbnail));
					return thumbnail;
				}
			}
		} finally {
			MediaDatabase.close(connection);
		}
		return null;
	}

	public static DLNAThumbnailInputStream getThumbnailInputStream(Long id) {
		DLNAThumbnail thumbnail = getThumbnail(id);
		return thumbnail != null ? new DLNAThumbnailInputStream(thumbnail) : null;
	}

	public static void resetLanguage() {
		for (WeakReference<DLNAThumbnail> wr : STORE.values()) {
			wr.clear();
		}
		STORE.clear();
		tempId.set(Long.MAX_VALUE);
		Connection connection = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			if (connection != null) {
				MediaTableFiles.resetLocalizedThumbnail(connection);
				MediaTableTVSeries.resetLocalizedThumbnail(connection);
				MediaTableThumbnails.cleanup(connection);
			}
		} finally {
			MediaDatabase.close(connection);
		}
	}

}
