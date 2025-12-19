package com.github.winplay02.gitcraft.manifest.metadata;

import java.time.ZonedDateTime;
import java.util.List;

public record VersionDetails(String id, String normalizedVersion, String displayVersion, ZonedDateTime releaseTime,
							 String releaseTarget, List<String> next, List<String> previous,
							 List<ManifestEntry> manifests, VersionInfo.Downloads downloads, List<LibraryMetadata> libraries,
							 boolean client, boolean server, boolean sharedMappings, boolean unobfuscated,
							 Protocol protocol, World world) {
	public record ManifestEntry(String url, String type, ZonedDateTime time, ZonedDateTime lastModified, String hash, int downloadsId, String downloads, String assetIndex, String assetHash) {
	}

	public record Protocol(String type, int version) {
	}

	public record World(String format, int version) {
	}
}
