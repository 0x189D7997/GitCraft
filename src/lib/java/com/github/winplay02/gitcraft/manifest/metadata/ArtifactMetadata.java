package com.github.winplay02.gitcraft.manifest.metadata;

public record ArtifactMetadata(String id, String path, String sha1, long size, long totalSize, String url) {
	// Asset index
	public ArtifactMetadata(String id, String sha1, long size, long totalSize, String url) {
		this(id, null, sha1, size, totalSize, url);
	}

	// Libraries
	public ArtifactMetadata(String path, String sha1, long size, String url) {
		this(null, path, sha1, size, 0, url);
	}

	// Client server and mappings
	public ArtifactMetadata(String sha1, long size, String url) {
		this(null, null, sha1, size, 0, url);
	}
}
