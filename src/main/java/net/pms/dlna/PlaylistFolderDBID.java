package net.pms.dlna;

import java.io.File;


public class PlaylistFolderDBID extends PlaylistFolder {

	public PlaylistFolderDBID(String name, String uri, int type) {
		super(name, uri, type);
	}

	public PlaylistFolderDBID(File f) {
		super(f);
	}

	@Override
	protected void resolveOnce() {
		resolveOnce(false);
	}
}
