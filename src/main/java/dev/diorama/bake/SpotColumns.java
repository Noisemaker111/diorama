package dev.diorama.bake;

import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * Each block column's spots as {@link TileSpots} last picked the whole column for a tile, so the
 * next pick of that tile takes the columns nothing has changed under. In the sky view a pick's ray
 * reads only blocks of its own column, from where it starts down to what it lands on, and where
 * those are in a chunk the map has no data for, the tile's saved spots in that column. So a column
 * keeps the chunks under its rays by {@link ChunkSnapshot#blocksToken} and, if it read them, those
 * saved spots, and is taken again only when the pick's settings, those chunks and those spots
 * are all the same.
 */
final class SpotColumns {
	/** How many ints the kept tiles may hold in all, about 8 MB; the least recently picked go first. */
	private static final long MAX_INTS = 2L << 20;

	private static final java.util.LinkedHashMap<TileKey, Tile> TILES = new java.util.LinkedHashMap<>(64, 0.75F, true);
	private static long ints;

	private SpotColumns() {
	}

	/** What a pick's rays depend on besides the chunks and the saved spots. */
	record Settings(Looks looks, double sin, double cos, Object facing, int startY, int limitY) {
	}

	/** One column, all its rows; see {@link #matches}. */
	static final class Column {
		final int[] spots = new int[TileSpots.ROWS];
		/** The chunks its rays read, z from cz0, by token, {@link ChunkBakeData#MISSING} where none. */
		int cz0;
		Object[] chunks;
		boolean usesSaved;
		int @Nullable [] saved;
	}

	static final class Tile {
		final Settings settings;
		final @Nullable Column[] columns = new Column[TileSpots.COLUMNS];
		long size;

		Tile(Settings settings) {
			this.settings = settings;
		}
	}

	static Settings settings(MapPick pick, Looks looks) {
		return new Settings(looks, Projection.sin(), Projection.cos(), Projection.facing(), pick.startY(), pick.limitY());
	}

	static synchronized @Nullable Tile get(TileKey key, Settings settings) {
		Tile tile = TILES.get(key);
		return tile != null && tile.settings.equals(settings) ? tile : null;
	}

	static synchronized void put(TileKey key, Tile tile) {
		Tile old = TILES.put(key, tile);
		if (old != null) {
			ints -= old.size;
		}
		ints += tile.size;
		java.util.Iterator<Tile> it = TILES.values().iterator();
		while (ints > MAX_INTS && it.hasNext()) {
			Tile t = it.next();
			if (t == tile) {
				break;
			}
			ints -= t.size;
			it.remove();
		}
	}

	/** Whether the column's chunks and the saved spots it read are the same now, as this pick reads them. */
	static boolean matches(Column column, MapPick pick, int x, int @Nullable [] saved, int index) {
		if (column.usesSaved && !MeshColumns.sameSpots(column.saved, saved, index)) {
			return false;
		}
		for (int k = 0; k < column.chunks.length; k++) {
			ChunkSnapshot chunk = pick.snapshot(x, (column.cz0 + k) << 4);
			if ((chunk == null ? ChunkBakeData.MISSING : chunk.blocksToken()) != column.chunks[k]) {
				return false;
			}
		}
		return true;
	}

	/** Records the chunks of z {@code z0} to {@code z1} a column's rays read, as this pick reads them. */
	static void depend(Column column, MapPick pick, int x, int z0, int z1) {
		column.cz0 = z0 >> 4;
		column.chunks = new Object[(z1 >> 4) - column.cz0 + 1];
		for (int k = 0; k < column.chunks.length; k++) {
			ChunkSnapshot chunk = pick.snapshot(x, (column.cz0 + k) << 4);
			column.chunks[k] = chunk == null ? ChunkBakeData.MISSING : chunk.blocksToken();
		}
	}
}
