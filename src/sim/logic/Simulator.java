package sim.logic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import logging.Log;
import sim.constants.Constants;
import sim.exceptions.SchematicException;
import sim.loading.Linker;
import utils.Tag;
import utils.Tag.Type;

public class Simulator {
	
	// Reflection intermediates
	private RWorld rWorld;
	private RBlock rBlock;
	private RChunk rChunk;
	private RProfiler rProfiler;
	private RChunkProvider rChunkProvider;
	private RTileEntity rTileEntity;
	private RNBTTags rNBTTags;
	private REntity rEntity;
	private RBootstrap rBootstrap;
	private RNextTickListEntry rNextTickListEntry;
	
	private HashMap<String, WorldInstance> loadedWorlds;

	public Simulator(String mcpFolder, String minecraftFolder) throws ClassNotFoundException, IOException, NoSuchFieldException, SecurityException, InstantiationException, IllegalAccessException, NoSuchMethodException, IllegalArgumentException, InvocationTargetException {
	
		// Extracts the classes from the jar, translates them and buffers them
		Log.i("Preparation start");
		
		loadedWorlds = new HashMap<String, WorldInstance>();
		
		Linker linker = new Linker(mcpFolder, minecraftFolder);
		
		rProfiler = new RProfiler(linker);
		
		rBlock = new RBlock(linker);
		
		rNBTTags = new RNBTTags(linker);
		
		rTileEntity = new RTileEntity(linker, rNBTTags.getReflClass());
		
		rChunk = new RChunk(linker, rBlock, rTileEntity.getReflClass());
		
		// Our implementation of chunkProvider, which will basically be our block input
		rChunkProvider = new RChunkProvider();
		
		// Making all objects ready, and linking chunkProvider to world already, so chunkProvider will be called from there
		rWorld = new RWorld(linker, rProfiler.getInstance());
		
		rEntity = new REntity(linker, rNBTTags.getReflClass(), rWorld.getReflClass());
		
		rBootstrap = new RBootstrap(linker);
		
		rNextTickListEntry = new RNextTickListEntry(linker);
		
//		for (int i = 0; i < 200; i++) {
//			try {
//				System.out.println(rItem.getItemString(rItem.getItemById(i)));
//			} catch (Exception e) {
//				System.out.println("fial");
//			}
//		}
	}
	
	/**
	 * Loads up a block of the same id
	 */
	public void createEmptyWorld(String name, int xSize, int ySize, int zSize) throws IOException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException {
		
		WorldInstance world = getWorldByName(name);
		
		int size = xSize * ySize * zSize;
		
		byte[] blockIds = new byte[size];
		byte[] blockData = new byte[size];
		
		for (int x = 0; x < xSize; x++)
			for (int y = 0; y < ySize; y++)
				for (int z = 0; z < zSize; z++) {
					blockIds[size] = 0;
					blockData[size] = 0;
				}
			
		setWorldBlocks(world, xSize, ySize, zSize, blockIds, blockData);	
	}
	
	private WorldInstance getWorldByName(String name) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException {
		
		if (loadedWorlds.containsKey(name))
			return loadedWorlds.get(name);
		
		Log.i("Creating new world");
		WorldInstance world = rWorld.createInstance(Constants.WORLDTYPEID, Constants.WORLDTYPE, Constants.GAMETYPE,
				Constants.SEED, Constants.WORLDPROVIDER, Constants.MAPFEATURESENABLED, Constants.HARDCOREENABLED, rChunk, rChunkProvider,
				rProfiler);
		loadedWorlds.put(name, world);
		
		return world;
	}
	
	public void destroy(String worldName) {
		
		loadedWorlds.remove(worldName);
	}
	
	public void setWorldFromFile(File schematicFile) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException, NoSuchAlgorithmException {
		
		try {
			
			if (!schematicFile.exists())
				throw new SchematicException("File not found: " + schematicFile.getPath());

			if (!schematicFile.canRead())
				throw new SchematicException("Unauthorized to read file: " + schematicFile.getPath());
			
			setWorld(schematicFile.getName(), new FileInputStream(schematicFile));
			
		} catch (IOException | SchematicException e) {
			
			Log.e(e.getMessage());
			e.printStackTrace();
			
		}
	}
	
	/**
	 * Loads in a schematic
	 * @param input A schematic-format inputstream (like FileInputStream from a .schematic)
	 * @return True if succesful, false otherwise
	 * @throws IOException
	 * @throws NoSuchAlgorithmException 
	 */
	public void setWorld(String worldName, InputStream input) throws IOException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException, NoSuchAlgorithmException {
		
		WorldInstance world = getWorldByName(worldName);
		
		Tag schematicTag = Tag.readFrom(input);
		
		if (Constants.DEBUG_MC_SCHEMATICS) {
			System.out.println("SET");
			schematicTag.print();
		}
		
		if (!schematicTag.getName().equals("Schematic"))
			Log.w("The root tag was not named 'Schematic', continuing anyway.");
		
		if (!schematicTag.findNextTagByName("Materials", null).getValue().equals("Alpha"))
			Log.w("The schematic is encoded for Minecraft classic or something else, which is not supported, but I'll try.");
		
		// Size
		world.setxSize((int) ((short) schematicTag.findNextTagByName("Width", null).getValue()));
		world.setySize((int) ((short) schematicTag.findNextTagByName("Height", null).getValue()));
		world.setzSize((int) ((short) schematicTag.findNextTagByName("Length", null).getValue()));
		
		// Blocks
		byte[] idsArray = (byte[]) schematicTag.findNextTagByName("Blocks", null).getValue();
		byte[] dataArray = (byte[]) schematicTag.findNextTagByName("Data", null).getValue();
		
		setWorldBlocks(world, world.getxSize(), world.getySize(), world.getzSize(), idsArray, dataArray);
		
		// TileEntities
		Tag tileEntities = schematicTag.findNextTagByName("TileEntities", null);
		rWorld.clearTileEntities(world);
		if (tileEntities != null)
			setWorldTileEntities(world, (Tag[]) tileEntities.getValue());
		
		// Entities
		Tag entities = schematicTag.findNextTagByName("Entities", null);
		rWorld.clearEntities(world);
		// TODO
//		if (entities != null)
//			setWorldEntities(world, (Tag[]) entities.getValue());
		
		// TileTicks
		Tag tileTicks = schematicTag.findNextTagByName("TileTicks", null);
		rWorld.clearTickEntries(world);
		if (tileTicks != null)
			setWorldTileTicks(world, (Tag[]) tileTicks.getValue());
	}
	
	/**
	 * Loads up a world from the SharedWorld object.
	 * 
	 * Assumes SharedWorld.blockIds has a constant width, height and length.
	 * Assumes SharedWorld.blockData has the exact same dimensions as SharedWorld.blockIds.
	 * @param world The world object to load in
	 */
	private void setWorldBlocks(WorldInstance world, int xSize, int ySize, int zSize, byte[] blockIds, byte[] blockDatas) throws ArrayIndexOutOfBoundsException, IllegalArgumentException, IllegalAccessException, InvocationTargetException, InstantiationException, IOException {
		
		rChunkProvider.clear();
		
		int chunksX = (xSize - 1) / 16 + 1;
		int chunksZ = (zSize - 1) / 16 + 1;
		
		// A chunk's height needs to be a quadratic of 2
		int height = 1;
		while (ySize > height)
			height *= 2;
		
		int chunkSize = 256 * height;		
		
		for (int chunkX = 0; chunkX < chunksX; chunkX++)
			for (int chunkZ = 0; chunkZ < chunksZ; chunkZ++) {
				
				int worldXoffset = chunkX * 16;
				int worldZoffset = chunkZ * 16;
				
				Object blocks = Array.newInstance(rBlock.getReflClass(), chunkSize);
				byte[] data = new byte[chunkSize];
				
				if (Constants.DEBUG_SCHEMATIC_DATA) {
					System.out.println("(" + chunkX + ", " + chunkZ + ")");
					System.out.println("id\tx\ty\tz\tchIndex\tschIndex");
				}
				
				for (int x = 0; x < 16; x++)
					for (int z = 0; z < 16; z++)
						for (int y = 0; y < height; y++) {
							
							int chunkIndex = x * height * 16 + z * height + y;
							int worldX = x + worldXoffset;
							int worldZ = z + worldZoffset;
							
							byte blockId, blockData;
							
							if (worldX >= xSize || worldZ >= zSize || y >= ySize) {
								
								blockId = 0;
								blockData = 0;
								
							} else {
								
								int schematicIndex = y * xSize * zSize + worldZ * xSize + worldX;
								
								blockId = blockIds[schematicIndex];
								blockData = blockDatas[schematicIndex];
								
								if (Constants.DEBUG_SCHEMATIC_DATA)
									System.out.println(blockId + "\t" + worldX + "\t" + y + "\t" + worldZ + "\t" + chunkIndex + "\t" + schematicIndex);
							}
							
							// TODO DEBUG
							Object block = rBlock.getBlock(blockId);
							Array.set(blocks, chunkIndex, block);
							data[chunkIndex] = blockData;
						}
				
				Object chunk = rChunk.createChunk(world.getWorld(), blocks, data, chunkX, chunkZ);
				
				rChunkProvider.addChunk(chunk, chunkX, chunkZ);
			}
	}

	/**
	 * Loads all tile entities from Tags, can run in a separate thread
	 * @param tags The tag array
	 */
	private void setWorldTileEntities(WorldInstance world, Tag[] tags) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException {
		
		for (int i = 0; i < tags.length; i++) {
			
			Tag schematicTag = tags[i];
			
			int x = (int) schematicTag.findTagByName("x").getValue();
			int z = (int) schematicTag.findTagByName("z").getValue();
			
			Object mcTag = rNBTTags.getMinecraftTagFromTag(schematicTag);
			
			Object chunk = rChunkProvider.getChunk(x, z);
			
			Object tileEntity = rTileEntity.createTileEntityFromNBT(mcTag);
			
//			try {
//				rTileEntity.debug(tileEntity);
//				Object itemsTag = rNBTTags.getTagList(mcTag, "Items");
//				Object itemTag = rNBTTags.getCompoundTagAtObject(itemsTag, 0);
//				System.out.println("Loading item: " + itemTag);
//				Object itemStack = rTileEntity.readFromNBT(itemTag);
//				System.out.println("Loaded item:  " + itemStack);
//				Object itemStack = rTileEntity.loadItemStackFromNBT(itemTag);
//				System.out.println(item);
//			} catch (Exception e) {
//				System.out.println("No inventory");
//				e.printStackTrace();
//			}

			rChunk.addTileEntity(chunk, tileEntity);
			
			// TODO remove this line
//			rWorld.addTileEntity(world, tileEntity);
		}
	}
	
	private void setWorldEntities(WorldInstance world, Tag[] tags) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException {
		
		for (int i = 0; i < tags.length; i++) {
			
			Tag schematicTag = tags[i];
			
			Object mcTag = rNBTTags.getMinecraftTagFromTag(schematicTag);
			
			Object entity = rEntity.createEntityFromNBT(mcTag, world.getWorld());
			
			rWorld.spawnEntityInWorld(world, entity);
		}
	}
	
	private void setWorldTileTicks(WorldInstance world, Tag[] tags) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		
		for (Tag tag : tags) {
			
			int xCoord		= (int) tag.findNextTagByName("x", null).getValue();
			int yCoord		= (int) tag.findNextTagByName("y", null).getValue();
			int zCoord		= (int) tag.findNextTagByName("z", null).getValue();
			
			int intBlock	= (int) tag.findNextTagByName("i", null).getValue();
			byte byteBlock	= (byte) intBlock;
			Object block	= rBlock.getBlock(byteBlock);
			
			int time		= (int) tag.findNextTagByName("t", null).getValue();
			int priority	= (int) tag.findNextTagByName("p", null).getValue();
			
			rWorld.addTickEntry(world, xCoord, yCoord, zCoord, block, time, priority);
		}
	}
	
	public void getWorld(String worldName, OutputStream os) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException {
		
		WorldInstance world = getWorldByName(worldName);
		
		int size = world.getxSize() * world.getySize() * world.getzSize();
		
		byte[] blockIds = new byte[size];
		byte[] blockData = new byte[size];
		
		int i = 0;
		
		if (Constants.DEBUG_SCHEMATIC_DATA)
			System.out.println("id\tx\ty\tz\tindex");
		
		for (int y = 0; y < world.getySize(); y++) {
			for (int z = 0; z < world.getzSize(); z++) {
				for (int x = 0; x < world.getxSize(); x++) {
					
					byte blockId = (byte) rBlock.getIdFromBlock(rWorld.getBlock(world, x, y, z));
					
					blockIds[i] = blockId;
					blockData[i] = (byte) rWorld.getBlockMetaData(world, x, y, z);
					
					if (Constants.DEBUG_SCHEMATIC_DATA)
						System.out.println(blockId + "\t" + x + "\t" + y + "\t" + z + "\t" + i);
					
					i++;
				}
			}
		}
		
			Tag tWidth	= new Tag(Tag.Type.TAG_Short, "Width" , (short) world.getxSize());
			Tag tHeight = new Tag(Tag.Type.TAG_Short, "Height", (short) world.getySize());
			Tag tLength = new Tag(Tag.Type.TAG_Short, "Length", (short) world.getzSize());
			
			Tag tMaterials = new Tag(Tag.Type.TAG_String, "Materials", "Alpha");				
			Tag tBlocks = new Tag(Tag.Type.TAG_Byte_Array, "Blocks", blockIds);
			Tag tData = new Tag(Tag.Type.TAG_Byte_Array, "Data", blockData);
			
			// Both of these can be null
			Tag tTileEntities = getWorldTileEntities(world);
			Tag tEntities = getWorldEntities(world);
			Tag tTileTicks = getWorldTileTicks(world);
			
			Tag tEnd = new Tag(Tag.Type.TAG_End, "", null);
			
		Tag tSchematic;
		
		tSchematic = new Tag(Tag.Type.TAG_Compound, "Schematic", new Tag[]{tHeight, tLength, tWidth, tMaterials, tData, tBlocks, tEnd});
		
		if (tTileEntities != null)
			tSchematic.addTag(tTileEntities);
		
		if (tEntities != null)
			tSchematic.addTag(tEntities);
		
		if (tTileTicks != null)
			tSchematic.addTag(tTileTicks);
		
		tSchematic.addTag(tEnd);
	
		if (Constants.DEBUG_MC_SCHEMATICS) {
			System.out.println("GET:");
			tSchematic.print();
		}
								
		tSchematic.writeTo(os);
		
		Log.i("Saving world");
	}
	
	private Tag getWorldTileEntities(WorldInstance world) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException {
		
		List<Object> tileEntities = rWorld.getLoadedTileEntities(world);//world.getLoadedTileEntities();
		
		// TODO temp fix, fix this properly
		if (tileEntities.size() == 0)
			return null;
		
		Tag[] payload = new Tag[tileEntities.size()];
		Iterator<Object> i = tileEntities.iterator();
		
		int j = 0;
		while (i.hasNext()) {
			
			Object mcTileEntity = i.next();
			Object mcTag = rNBTTags.newInstance();
			
			rTileEntity.getNBTFromTileEntity(mcTileEntity, mcTag);
			
			payload[j] = rNBTTags.getTagFromMinecraftTag(mcTag);
			
			j++;			
		}
		
		Tag tTileEntities = new Tag(Tag.Type.TAG_List, "TileEntities", payload);
		
		return tTileEntities;		
	}
	
	private Tag getWorldEntities(WorldInstance world) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, IOException {
		List<Object> entities = world.getLoadedEntities();
		
		if (entities.size() == 0)
			return null;
		
		Tag[] payload = new Tag[entities.size()];
		Iterator<Object> i = entities.iterator();
		
		int j = 0;
		while (i.hasNext()) {
			
			Object entity = i.next();
			Object mcTag = rNBTTags.newInstance();
			
			rEntity.getNBTFromEntity(entity, mcTag);
			
			payload[j] = rNBTTags.getTagFromMinecraftTag(mcTag);
			j++;
		}
		
		Tag tEntities = new Tag(Tag.Type.TAG_List, "Entities", payload);
		
		return tEntities;
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Tag getWorldTileTicks(WorldInstance world) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
		
		Set tickTicks = world.getPendingTickListEntries();
		int size = tickTicks.size();
		
		if (size == 0)
			return null;
		
		Iterator<Object> tileTicksIterator = tickTicks.iterator();
		
		Tag[] tTileTickArray = new Tag[size];
		int index = 0;
		
		while (tileTicksIterator.hasNext()) {
			
			Object tileTick = tileTicksIterator.next();
			
			Tag tXCoord		= new Tag(Type.TAG_Int, "x", rNextTickListEntry.getXCoord(tileTick));
			Tag tYCoord		= new Tag(Type.TAG_Int, "y", rNextTickListEntry.getYCoord(tileTick));
			Tag tZCoord		= new Tag(Type.TAG_Int, "z", rNextTickListEntry.getZCoord(tileTick));
			Tag tBlock		= new Tag(Type.TAG_Int, "i", rBlock.getIdFromBlock(rNextTickListEntry.getBlock(tileTick)));
			Tag tTime		= new Tag(Type.TAG_Int, "t", (int) (rNextTickListEntry.getScheduledTime(tileTick) - world.getWorldTime()));
			Tag tPriority	= new Tag(Type.TAG_Int, "p", rNextTickListEntry.getPriority(tileTick));
			Tag tEnd		= new Tag(Tag.Type.TAG_End, "", null);
			
			Tag tTileTick = new Tag(Type.TAG_Compound, null, new Tag[]{tXCoord, tYCoord, tZCoord, tBlock, tTime, tPriority, tEnd});
			
			tTileTickArray[index++] = tTileTick;
		}
		
		Tag tTileTicks = new Tag(Type.TAG_List, "TileTicks", tTileTickArray);
		
		return tTileTicks;
	}
	
	/**
	 * Ticks the given world 2 gameticks into the future.
	 * @param worldName The name of the world.
	 */
	public void tickWorld(String worldName) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException {
		
		WorldInstance world = getWorldByName(worldName);
		
		rWorld.tickUpdates(world, 2l);
//		rWorld.tickEntities(world);
	}
	
	public void setBlock(String worldName, int x, int y, int z, byte id, byte data) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException {
		
		Object block = rBlock.getBlock(id);
		WorldInstance world = getWorldByName(worldName);
		
		rWorld.setBlock(world, x, y, z, block, data, true, true);
	}
}
