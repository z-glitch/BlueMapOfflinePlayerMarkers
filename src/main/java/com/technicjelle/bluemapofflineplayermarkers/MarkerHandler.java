package com.technicjelle.bluemapofflineplayermarkers;

import com.flowpowered.nbt.*;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.technicjelle.BMUtils;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class MarkerHandler {
	private final BlueMapOfflinePlayerMarkers plugin;

	MarkerHandler(BlueMapOfflinePlayerMarkers plugin) {
		this.plugin = plugin;
	}

	/**
	 * Adds a player marker to the map.
	 *
	 * @param player The player to add the marker for.
	 */
	public void add(Player player) {
		add(player, player.getLocation(), player.getGameMode());
	}

	/**
	 * Adds a player marker to the map.
	 *
	 * @param player   The player to add the marker for.
	 * @param location The location to put the marker at.
	 * @param gameMode The game mode of the player.
	 */
	public void add(OfflinePlayer player, Location location, GameMode gameMode) {
		Optional<BlueMapAPI> optionalApi = BlueMapAPI.getInstance();
		if (optionalApi.isEmpty()) {
			plugin.getLogger().warning("Tried to add a marker, but BlueMap wasn't loaded!");
			return;
		}
		BlueMapAPI api = optionalApi.get();

		//If this player's visibility is disabled on the map, don't add the marker.
		if (!api.getWebApp().getPlayerVisibility(player.getUniqueId())) return;

		//If this player's game mode is disabled on the map, don't add the marker.
		if (plugin.getCurrentConfig().hiddenGameModes.contains(gameMode)) return;

		// Get BlueMapWorld for the location
		BlueMapWorld blueMapWorld = api.getWorld(location.getWorld()).orElse(null);
		if (blueMapWorld == null) return;

		String playerName = player.getName();
		if (playerName == null) {
			// As a last resort, go through white/blacklist + operators list.
			// TODO: Should use an actual library as a fix instead.
			Optional<OfflinePlayer> offlinePlayerInServerLists = Stream.of(
					Bukkit.getWhitelistedPlayers(), Bukkit.getBannedPlayers(), Bukkit.getOperators())
					.flatMap(Set::stream)
					.filter(offlinePlayers -> offlinePlayers.getUniqueId().equals(player.getUniqueId())).findFirst();
			if (offlinePlayerInServerLists.isPresent()) {
				playerName = offlinePlayerInServerLists.get().getName();
			}
		}
		// Create marker-template
		// (add 1.8 to y to place the marker at the head-position of the player, like BlueMap does with its player-markers)
		POIMarker.Builder markerBuilder = POIMarker.builder()
				.label(playerName != null ? playerName : player.getUniqueId().toString())
				.detail((playerName != null ? playerName : "[Unknown]") + " <i>(offline)</i><br>"
						+ "<bmopm-datetime data-timestamp=" + player.getLastPlayed() + "></bmopm-datetime>")
				.styleClasses("bmopm-offline-player")
				.position(location.getX(), location.getY() + 1.8, location.getZ());

		// Create an icon and marker for each map of this world
		// We need to create a separate marker per map, because the map-storage that the icon is saved in
		// is different for each map
		for (BlueMapMap map : blueMapWorld.getMaps()) {
			markerBuilder.icon(BMUtils.getPlayerHeadIconAddress(api, player.getUniqueId(), map), 0, 0); // centered with CSS instead

			// get marker-set (or create new marker set if none found)
			MarkerSet markerSet = map.getMarkerSets().computeIfAbsent(Config.MARKER_SET_ID, id -> MarkerSet.builder()
					.label(plugin.getCurrentConfig().markerSetName)
					.toggleable(plugin.getCurrentConfig().toggleable)
					.defaultHidden(plugin.getCurrentConfig().defaultHidden)
					.build());

			// add marker
			markerSet.put(player.getUniqueId().toString(), markerBuilder.build());
		}

		plugin.getLogger().info("Marker for "
				+ (playerName != null ? playerName : player.getUniqueId().toString())
				+ " added");
	}


	/**
	 * Removes a player marker from the map.
	 *
	 * @param player The player to remove the marker for.
	 */
	public void remove(Player player) {
		Optional<BlueMapAPI> optionalApi = BlueMapAPI.getInstance();
		if (optionalApi.isEmpty()) {
			plugin.getLogger().warning("Tried to remove a marker, but BlueMap wasn't loaded!");
			return;
		}
		BlueMapAPI api = optionalApi.get();

		// remove all markers with the players uuid
		for (BlueMapMap map : api.getMaps()) {
			MarkerSet set = map.getMarkerSets().get(Config.MARKER_SET_ID);
			if (set != null) set.remove(player.getUniqueId().toString());
		}

		plugin.getLogger().info("Marker for " + player.getName() + " removed");
	}

	/**
	 * Load in markers of all offline players by going through the playerdata NBT
	 */
	public void loadOfflineMarkers() {
		//I really don't like "getWorlds().get(0)" as a way to get the main world, but as far as I can tell there is no other way
		File playerDataFolder = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
		//Return if playerdata is missing for some reason.
		if (!playerDataFolder.exists() || !playerDataFolder.isDirectory()) return;

		for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
			//If player is online, ignore (I don't know why the method is called "getOfflinePlayers" when it also contains all online players...)
			if (op.isOnline()) continue;

			long timeSinceLastPlayed = System.currentTimeMillis() - op.getLastPlayed();
//			logger.info("Player " + op.getName() + " was last seen " + timeSinceLastPlayed + "ms ago");
			if (plugin.getCurrentConfig().expireTimeInHours > 0 && timeSinceLastPlayed > plugin.getCurrentConfig().expireTimeInHours * 60 * 60 * 1000) {
				plugin.getLogger().fine("Player " + op.getName() + " was last seen too long ago, skipping");
				continue;
			}

			File dataFile = new File(playerDataFolder, op.getUniqueId() + ".dat");

			//Failsafe if playerdata doesn't exist (should be impossible but whatever)
			if (!dataFile.exists()) continue;

			CompoundMap nbtData;
			try (FileInputStream fis = new FileInputStream(dataFile);
				 NBTInputStream nbtInputStream = new NBTInputStream(fis)) {
				nbtData = ((CompoundTag) nbtInputStream.readTag()).getValue();
			} catch (IOException e) {
				e.printStackTrace();
				continue;
			}

			//Collect data
			@SuppressWarnings("unchecked") //Apparently this is just how it should be https://discord.com/channels/665868367416131594/771451216499965953/917450319259115550
			Optional<World> worldOptional;

			Optional<Tag<?>> positionOptional = Optional.ofNullable(nbtData.get("Pos"));
			Optional<Tag<?>> gameModeIntOptional = Optional.ofNullable(nbtData.get("playerGameType"));
			Optional<Tag<?>> worldUUIDLeastOptional = Optional.ofNullable(nbtData.get("WorldUUIDLeast"));
			Optional<Tag<?>> worldUUIDMostOptional = Optional.ofNullable(nbtData.get("WorldUUIDMost"));

			if (positionOptional.isEmpty()) {
				plugin.getLogger().warning("Player " + op.getName() + " has no (or corrupted) position data!, skipping...");
				continue;
			} else if (gameModeIntOptional.isEmpty()
					|| !Integer.class.isAssignableFrom(gameModeIntOptional.get().getValue().getClass())) {
				plugin.getLogger().warning("Player " + op.getName() + " has no (or corrupted) gamemode data!, skipping...");
				continue;
			} else if (worldUUIDLeastOptional.isEmpty() || worldUUIDMostOptional.isEmpty()) {
				// Old playerdata present!
				plugin.getLogger().fine("Can't find WorldUUID for " + op.getUniqueId() + ".  Using Dimension instead if available.");
				Optional<Tag<?>> dimensionOptional = Optional.ofNullable(nbtData.get("Dimension"));
				if (dimensionOptional.isEmpty()
						|| !Integer.class.isAssignableFrom(dimensionOptional.get().getValue().getClass())) {
					plugin.getLogger().warning("Player " + op.getName() + " has no (or corrupted) dimension data!, skipping...");
					continue;
				}

				int dimension = (int) dimensionOptional.get().getValue();
				worldOptional = Optional.ofNullable(getWorldByDimension(dimension));
			} else if (!Long.class.isAssignableFrom(worldUUIDLeastOptional.get().getValue().getClass())
				|| !Long.class.isAssignableFrom(worldUUIDMostOptional.get().getValue().getClass())) {
				// Should not happen.
				plugin.getLogger().warning("Player " + op.getName() + " has corrupted dimension data!, skipping...");
				continue;
			} else {
				// 'Normal' flow
				long worldUUIDLeast = (long) worldUUIDLeastOptional.get().getValue();
				long worldUUIDMost = (long) worldUUIDMostOptional.get().getValue();
				UUID worldUUID = new UUID(worldUUIDMost, worldUUIDLeast);
				worldOptional = Optional.ofNullable(Bukkit.getWorld(worldUUID));
			}

			//World doesn't exist
			if (worldOptional.isEmpty()) {
				plugin.getLogger().warning(String.format("World not found for player: %s", op.getName()));
				continue;
			}
			// Build position
			List<Double> position = new ArrayList<>();
			if (!Collection.class.isAssignableFrom(positionOptional.get().getValue().getClass())) {
				plugin.getLogger().warning(String.format("Position for player corrupt!: %s", op.getName()));
				continue;
			}
			boolean corrupt = false;
			for (Object o : (Collection<?>) positionOptional.get().getValue()) {
				if (!Tag.class.isAssignableFrom(o.getClass())) {
					corrupt = true;
					break;
				}
				Tag<?> t = (Tag<?>) o;
				if (t.getType() != TagType.TAG_DOUBLE) {
					corrupt = true;
					break;
				}
				DoubleTag dt = (DoubleTag) t;
				position.add(dt.getValue());
			}
			if (corrupt) {
				plugin.getLogger().warning(String.format("Position for player corrupt!: %s", op.getName()));
				continue;
			} else if (position.size() != 3) {
				plugin.getLogger().warning(String.format("Position for player corrupt!: %s", op.getName()));
				continue;
			}

			//Convert to location
			Location loc = new Location(worldOptional.get(), position.get(0), position.get(1), position.get(2));

			//Convert to game mode
			@SuppressWarnings("deprecation")
			GameMode gameMode = GameMode.getByValue((int) gameModeIntOptional.get().getValue());

			//Add marker
			add(op, loc, gameMode);
		}
	}
	/**
	 * Gets the first world found with a specified dimension.
	 *
	 * @param dimension The dimension to search for.
	 */
	@SuppressWarnings("deprecation")
	private World getWorldByDimension(int dimension) {
		for (World w : Bukkit.getWorlds())
			if (w.getEnvironment().getId() == dimension)
				return w;
		plugin.getLogger().fine("Can't find a good candidate world for dimension " + dimension);
		return null;
	}
}
