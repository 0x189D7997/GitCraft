package com.github.winplay02.gitcraft;

import com.github.winplay02.gitcraft.exceptions.ExceptionsFlavour;
import com.github.winplay02.gitcraft.graph.AbstractVersionGraph;
import com.github.winplay02.gitcraft.manifest.ManifestSource;
import com.github.winplay02.gitcraft.manifest.MetadataProvider;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.nests.NestsFlavour;
import com.github.winplay02.gitcraft.signatures.SignaturesFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.unpick.UnpickFlavour;
import com.github.winplay02.gitcraft.util.MiscHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class MinecraftVersionGraph extends AbstractVersionGraph<OrderedVersion> {


	private MinecraftVersionGraph() {
		super();
	}

	private MinecraftVersionGraph(MinecraftVersionGraph previous, Predicate<OrderedVersion> predicate, String... tags) {
		super(previous, predicate, tags);
		this.findBranchStructure();
	}

	private void findBranchStructure() {
		this.roots = this.findRootVertices();

		this.pathsToTip = new HashMap<>();

		Map<OrderedVersion, Integer> pathsToTip = new HashMap<>();

		Set<OrderedVersion> tips = this.findPathLengths(this.roots, new HashMap<>(), new HashSet<>(), this::getFollowingVertices);
		Set<OrderedVersion> roots = this.findPathLengths(tips, pathsToTip, new HashSet<>(), this::getPreviousVertices);

		if (!roots.equals(this.roots)) {
			MiscHelper.panic("Minecraft version graph is inconsistently structured! Tree roots: %s. Walk roots: %s.", this.roots, roots);
		}

		this.markRoots(pathsToTip, this.roots);
	}

	private Set<OrderedVersion> findPathLengths(Set<OrderedVersion> startingLayer, Map<OrderedVersion, Integer> pathLengths, Set<OrderedVersion> branchPoints, Function<OrderedVersion, Set<OrderedVersion>> nextVersionsGetter) {
		// do a breadth first walk away from starting layer and
		// set the path length for each version
		// we end up with a map storing the longest path to the
		// starting layer for each version

		Set<OrderedVersion> currentLayer = new LinkedHashSet<>(startingLayer);
		Set<OrderedVersion> nextLayer = new LinkedHashSet<>();
		Set<OrderedVersion> ends = new LinkedHashSet<>();

		int pathLength = 0;

		while (!currentLayer.isEmpty()) {
			nextLayer.clear();

			for (OrderedVersion version : currentLayer) {
				Set<OrderedVersion> nextVersions = nextVersionsGetter.apply(version);

				if (nextVersions.isEmpty()) {
					ends.add(version);
				} else {
					nextLayer.addAll(nextVersions);

					if (nextVersions.size() > 1) {
						branchPoints.add(version);
					}
				}

				pathLengths.put(version, pathLength);
			}

			currentLayer.clear();
			currentLayer.addAll(nextLayer);

			pathLength++;
		}

		return ends;
	}

	private void markRoots(Map<OrderedVersion, Integer> allPathsToTip, Set<OrderedVersion> roots) {
		// used to sort roots
		for (OrderedVersion root : roots) {
			this.pathsToTip.put(root, allPathsToTip.get(root));
		}
	}

	private boolean shouldExcludeFromMainBranch(OrderedVersion mcVersion) {
		return GitCraft.getApplicationConfiguration().manifestSource().getMetadataProvider().shouldExcludeFromMainBranch(mcVersion);
	}

	public HashMap<OrderedVersion, Integer> pathsToTip = new HashMap<>();

	public static MinecraftVersionGraph createFromMetadata(Executor executor, MetadataProvider<OrderedVersion> provider) throws IOException {
		MinecraftVersionGraph graph = new MinecraftVersionGraph();
		// Compatibility with existing repositories
		if (provider.getSource() != ManifestSource.MOJANG) {
			graph.repoTags.add(String.format("manifest_%s", provider.getInternalName()));
		}
		TreeSet<OrderedVersion> metaVersions = new TreeSet<>(provider.getVersions(executor).values());
		TreeSet<OrderedVersion> metaVersionsMainline = new TreeSet<>(provider.getVersions(executor).values().stream().filter(value -> !provider.shouldExcludeFromMainBranch(value)).toList());
		for (OrderedVersion version : metaVersions) {
			graph.edgesBack.computeIfAbsent(version, _ -> new TreeSet<>());
			graph.edgesFw.computeIfAbsent(version, _ -> new TreeSet<>());
			for (OrderedVersion previousVersion : findPreviousVersions(provider, metaVersionsMainline, version)) {
				graph.edgesBack.computeIfAbsent(version, _ -> new TreeSet<>()).add(previousVersion);
				graph.edgesFw.computeIfAbsent(previousVersion, _ -> new TreeSet<>()).add(version);
			}
		}
		graph.testGraphConnectivity();
		graph.validateNoCycles();
		graph.findBranchStructure();
		return graph;
	}

	private static List<OrderedVersion> findPreviousVersions(MetadataProvider<OrderedVersion> metadata, NavigableSet<OrderedVersion> versions, OrderedVersion version) {
		return findPreviousVersions(metadata, versions, version, version);
	}

	private static List<OrderedVersion> findPreviousVersions(MetadataProvider<OrderedVersion> metadata, NavigableSet<OrderedVersion> versions, OrderedVersion version, OrderedVersion target) {
		List<OrderedVersion> previousVersions = new ArrayList<>();

		// some manifest providers have built-in ordering for all or certain versions
		List<OrderedVersion> parentVersions = metadata.getParentVersions(version);

		// if that is not the case, use the ordering from the semantic versioning
		if (parentVersions == null) {
			OrderedVersion parentVersion = versions.lower(version);

			parentVersions = (parentVersion == null)
				? Collections.emptyList()
				: List.of(parentVersion);
		}

		// if a parent version cannot form a valid edge with the target,
		// find valid edges in that version's parent versions, recursively
		for (OrderedVersion parentVersion : parentVersions) {
			if (isValidEdge(parentVersion, target)) {
				previousVersions.add(parentVersion);
			} else {
				previousVersions.addAll(findPreviousVersions(metadata, versions, parentVersion, version));
			}
		}

		return previousVersions;
	}

	private static boolean isValidEdge(OrderedVersion v1, OrderedVersion v2) {
		// TODO: allow disabling this check through the config/run args?
		return v1.hasSideInCommon(v2);
	}

	public MinecraftVersionGraph filterMapping(MappingFlavour mappingFlavour, MappingFlavour[] mappingFallback) {
		return new MinecraftVersionGraph(this, (entry -> mappingFlavour.exists(entry) || (mappingFallback != null && mappingFallback.length > 0 && Arrays.stream(mappingFallback).anyMatch(mapping -> mapping.exists(entry)))));
	}

	public MinecraftVersionGraph filterUnpick(UnpickFlavour unpickFlavour, UnpickFlavour[] unpickFallback) {
		return new MinecraftVersionGraph(this, (entry -> unpickFlavour.exists(entry) || (unpickFallback != null && unpickFallback.length > 0 && Arrays.stream(unpickFallback).anyMatch(unpick -> unpick.exists(entry)))));
	}

	public MinecraftVersionGraph filterMainlineVersions() {
		return new MinecraftVersionGraph(this, this::isOnMainBranch);
	}

	public MinecraftVersionGraph filterMinVersion(OrderedVersion version) {
		return new MinecraftVersionGraph(this, (entry -> entry.compareTo(version) >= 0), String.format("min-%s", version.launcherFriendlyVersionName()));
	}

	public MinecraftVersionGraph filterMaxVersion(OrderedVersion version) {
		return new MinecraftVersionGraph(this, (entry -> entry.compareTo(version) <= 0), String.format("max-%s", version.launcherFriendlyVersionName()));
	}

	public MinecraftVersionGraph filterOnlyVersion(OrderedVersion... version) {
		TreeSet<OrderedVersion> versionList = new TreeSet<>(Arrays.asList(version));
		return new MinecraftVersionGraph(this, versionList::contains, versionList.stream().map(OrderedVersion::launcherFriendlyVersionName).collect(Collectors.joining("-")));
	}

	public MinecraftVersionGraph filterExcludeVersion(OrderedVersion... version) {
		TreeSet<OrderedVersion> versionList = new TreeSet<>(Arrays.asList(version));
		if (versionList.isEmpty()) {
			return this;
		}
		return new MinecraftVersionGraph(this, (entry -> !versionList.contains(entry)), "exclude-" + versionList.stream().map(OrderedVersion::launcherFriendlyVersionName).collect(Collectors.joining("-")));
	}

	public MinecraftVersionGraph filterStableRelease() {
		return new MinecraftVersionGraph(this, (entry -> !entry.isSnapshotOrPending()), "stable");
	}

	public MinecraftVersionGraph filterSnapshots() {
		return new MinecraftVersionGraph(this, OrderedVersion::isSnapshotOrPending, "snapshot");
	}

	public OrderedVersion getMainRootVersion() {
		if (this.roots.isEmpty()) {
			MiscHelper.panic("MinecraftVersionGraph does not contain a root version node");
		}
		return this.roots.stream().max((v1, v2) -> this.pathsToTip.get(v1) - this.pathsToTip.get(v2)).get();
	}

	public boolean isOnMainBranch(OrderedVersion mcVersion) {
		return this.roots.contains(this.walkBackToBranchPoint(mcVersion));
	}

	public OrderedVersion walkBackToRoot(OrderedVersion mcVersion) {
		return this.walkBackToBranchPoint(mcVersion, true, false);
	}

	public OrderedVersion walkBackToMainlineRoot(OrderedVersion mcVersion) {
		return this.walkBackToBranchPoint(mcVersion, true, true);
	}

	public OrderedVersion walkBackToBranchPoint(OrderedVersion mcVersion) {
		return this.walkBackToBranchPoint(mcVersion, false, false);
	}

	public OrderedVersion walkBackToFirstBranchPoint(OrderedVersion mcVersion) {
		return this.walkBackToBranchPoint(mcVersion, false, true);
	}

	private OrderedVersion walkBackToBranchPoint(OrderedVersion mcVersion, boolean root, boolean stopOnMerge) {

		// the following logic assumes there are no secondary branches
		// this is currently true for all supported manifests

		Set<OrderedVersion> previous_versions = this.getPreviousVertices(mcVersion);

		// no branches were merged into this version
		if (previous_versions.size() == 1) {
			OrderedVersion previous = previous_versions.iterator().next();
			// detect if this is a first version on a non-mainline branch
			if (!root // continue to root even when branch changes
				&& !this.isMainline(mcVersion) // continue to root for versions on main branch
				&& this.isMainline(previous)) { // continue if previous version is still on the branch
				return mcVersion;
			}
			return this.walkBackToBranchPoint(previous, root, stopOnMerge);
		}

		// at least two branches are merged into this version
		// we need to carefully select the path and make sure the choice of branch is unambiguous
		// stopOnMerge = false: merges from mainline should not prevent tracking of the branch
		// stopOnMerge = true: return if not looking for root otherwise stick to main branch as soon as we can
		if (previous_versions.size() > 1) {
			boolean mainline = this.isMainline(mcVersion);

			// stop if there is merge into this branch
			// no need to check previous vertices
			if (stopOnMerge && !root && !mainline) {
				return mcVersion;
			}

			OrderedVersion exclusiveWalkBack = null;
			int exclusiveCount = 0;
			for (OrderedVersion previous : previous_versions) {
				boolean p_mainline = this.isMainline(previous);
				if (p_mainline && (mainline || stopOnMerge)) { // stay on main branch or switch to it when looking for root with stopOnMerge
					return walkBackToBranchPoint(previous, root, stopOnMerge);
				}
				if (!mainline && !p_mainline) { // follow the branch and make sure there are no other non-mainline branches
					exclusiveWalkBack = previous;
					exclusiveCount++;
				}
			}
			if (exclusiveCount == 1 && !stopOnMerge) { // double check as this should not be reachable with stopOnMerge
				return walkBackToBranchPoint(exclusiveWalkBack, root, stopOnMerge);
			}

			MiscHelper.panic("walkBackToBranchPoint could not determine which branch to follow for %s", mcVersion.friendlyVersion());
		}

		// there are no previous vertices, this is a root version
		return mcVersion;
	}

	public OrderedVersion walkForwardToTip(OrderedVersion mcVersion) {
		return this.walkForwardToMergePoint(mcVersion, true, false);
	}

	public OrderedVersion walkForwardToMainlineTip(OrderedVersion mcVersion) {
		return this.walkForwardToMergePoint(mcVersion, true, true);
	}

	public OrderedVersion walkForwardToMergePoint(OrderedVersion mcVersion) {
		return this.walkForwardToMergePoint(mcVersion, false, false);
	}

	public OrderedVersion walkForwardToFirstMergePoint(OrderedVersion mcVersion) {
		return this.walkForwardToMergePoint(mcVersion, false, true);
	}

	private OrderedVersion walkForwardToMergePoint(OrderedVersion mcVersion, boolean tip, boolean stopOnMerge) {

		// the following logic assumes there are no secondary branches
		// this is currently true for all supported manifests

		Set<OrderedVersion> next_versions = this.getFollowingVertices(mcVersion);

		// no branches start from this version
		if (next_versions.size() == 1) {
			OrderedVersion next = next_versions.iterator().next();
			// detect if this is a first version on a non-mainline branch
			if (!tip // continue through merge point
				&& !this.isMainline(mcVersion) // continue to root for versions on main branch
				&& this.isMainline(next)) { // continue if next version is not merged into mainline
				return mcVersion;
			} else {
				return this.walkForwardToMergePoint(next, tip, stopOnMerge);
			}
		}

		// at least two branches start from this version
		// we need to carefully select the path and make sure the choice of branch is unambiguous
		// stopOnMerge = false: merges to mainline should not prevent tracking of the branch
		// stopOnMerge = true: return if not looking for tip otherwise stick to main branch as soon as we can
		if (next_versions.size() > 1) {
			boolean mainline = this.isMainline(mcVersion);

			// stop if this branch is merged or splits
			// no need to check following vertices
			if (stopOnMerge && !tip && !mainline) {
				return mcVersion;
			}

			OrderedVersion exclusiveWalkBack = null;
			int exclusiveCount = 0;
			for (OrderedVersion next : next_versions) {
				boolean n_mainline = this.isMainline(next);
				if (n_mainline && (mainline || stopOnMerge)) { // stay on main branch or switch to it when looking for tip with stopOnMerge
					return walkBackToBranchPoint(next, tip, stopOnMerge);
				}
				if (!mainline && !n_mainline) { // follow the branch and make sure there are no other non-mainline branches
					exclusiveWalkBack = next;
					exclusiveCount++;
				}
			}
			if (exclusiveCount == 1 && !stopOnMerge) { // double check as this should not be reachable with stopOnMerge
				return walkBackToBranchPoint(exclusiveWalkBack, tip, stopOnMerge);
			}

			MiscHelper.panic("walkBackToBranchPoint could not determine which branch to follow for %s", mcVersion.friendlyVersion());
		}

		// there are no following vertices, this is a tip version
		return mcVersion;
	}

	public OrderedVersion getMinecraftVersionByName(String versionName) {
		if (versionName == null) {
			return null;
		}
		return this.edgesBack.keySet().stream().filter(value -> value.launcherFriendlyVersionName().equalsIgnoreCase(versionName)).findFirst().orElse(null);
	}

	public OrderedVersion getMinecraftVersionBySemanticVersion(String semanticVersion) {
		if (semanticVersion == null) {
			return null;
		}
		return this.edgesBack.keySet().stream().filter(value -> value.semanticVersion().equalsIgnoreCase(semanticVersion)).findFirst().orElse(null);
	}

	public String repoTagsIdentifier(MappingFlavour mappingFlavour, MappingFlavour[] mappingFallback, boolean patchLvt, SignaturesFlavour signaturesFlavour, NestsFlavour nestsFlavour, ExceptionsFlavour exceptionsFlavour, boolean preening) {
		List<String> sortedTags = new ArrayList<>();
		sortedTags.add(mappingFlavour.toString());
		if (mappingFallback != null && mappingFallback.length > 0) {
			sortedTags.add(String.format("fallback-%s", Arrays.stream(mappingFallback).map(Object::toString).collect(Collectors.joining("-"))));
		}
		sortedTags.addAll(this.repoTags.stream().filter(tag -> !tag.equals(mappingFlavour.toString())).toList());
		if (patchLvt) {
			sortedTags.add("lvt");
		}
		if (signaturesFlavour != null && signaturesFlavour != SignaturesFlavour.NONE) {
			sortedTags.add(String.format("sig_%s", signaturesFlavour));
		}
		if (nestsFlavour != null && nestsFlavour != NestsFlavour.NONE) {
			sortedTags.add(String.format("nests_%s", nestsFlavour));
		}
		if (exceptionsFlavour != null && exceptionsFlavour != ExceptionsFlavour.NONE) {
			sortedTags.add(String.format("exc_%s", exceptionsFlavour));
		}
		if (preening) {
			sortedTags.add("preened");
		}
		return String.join("-", sortedTags);
	}
}
