package com.sk89q.worldedit.bukkit.adapter.impl.fawe.regen;

import com.fastasyncworldedit.bukkit.adapter.Regenerator;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.util.TaskManager;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import com.sk89q.worldedit.bukkit.adapter.impl.fawe.BukkitGetBlocks_1_16_5;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.RegenOptions;
import net.minecraft.server.v1_16_R3.Area;
import net.minecraft.server.v1_16_R3.AreaContextTransformed;
import net.minecraft.server.v1_16_R3.AreaFactory;
import net.minecraft.server.v1_16_R3.AreaTransformer8;
import net.minecraft.server.v1_16_R3.BiomeBase;
import net.minecraft.server.v1_16_R3.BiomeRegistry;
import net.minecraft.server.v1_16_R3.Chunk;
import net.minecraft.server.v1_16_R3.ChunkConverter;
import net.minecraft.server.v1_16_R3.ChunkCoordIntPair;
import net.minecraft.server.v1_16_R3.ChunkGenerator;
import net.minecraft.server.v1_16_R3.ChunkGeneratorAbstract;
import net.minecraft.server.v1_16_R3.ChunkProviderFlat;
import net.minecraft.server.v1_16_R3.ChunkProviderServer;
import net.minecraft.server.v1_16_R3.ChunkStatus;
import net.minecraft.server.v1_16_R3.Convertable;
import net.minecraft.server.v1_16_R3.DefinedStructureManager;
import net.minecraft.server.v1_16_R3.DynamicOpsNBT;
import net.minecraft.server.v1_16_R3.GenLayer;
import net.minecraft.server.v1_16_R3.GenLayers;
import net.minecraft.server.v1_16_R3.GeneratorSettingBase;
import net.minecraft.server.v1_16_R3.GeneratorSettings;
import net.minecraft.server.v1_16_R3.GeneratorSettingsFlat;
import net.minecraft.server.v1_16_R3.IChunkAccess;
import net.minecraft.server.v1_16_R3.IRegistry;
import net.minecraft.server.v1_16_R3.IRegistryCustom;
import net.minecraft.server.v1_16_R3.LightEngineThreaded;
import net.minecraft.server.v1_16_R3.LinearCongruentialGenerator;
import net.minecraft.server.v1_16_R3.MinecraftKey;
import net.minecraft.server.v1_16_R3.MinecraftServer;
import net.minecraft.server.v1_16_R3.NBTBase;
import net.minecraft.server.v1_16_R3.NBTTagCompound;
import net.minecraft.server.v1_16_R3.NoiseGeneratorPerlin;
import net.minecraft.server.v1_16_R3.ProtoChunk;
import net.minecraft.server.v1_16_R3.RegistryGeneration;
import net.minecraft.server.v1_16_R3.RegistryMaterials;
import net.minecraft.server.v1_16_R3.RegistryReadOps;
import net.minecraft.server.v1_16_R3.ResourceKey;
import net.minecraft.server.v1_16_R3.World;
import net.minecraft.server.v1_16_R3.WorldChunkManager;
import net.minecraft.server.v1_16_R3.WorldChunkManagerOverworld;
import net.minecraft.server.v1_16_R3.WorldDataServer;
import net.minecraft.server.v1_16_R3.WorldDimension;
import net.minecraft.server.v1_16_R3.WorldLoadListener;
import net.minecraft.server.v1_16_R3.WorldServer;
import net.minecraft.server.v1_16_R3.WorldSettings;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_16_R3.CraftServer;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.generator.CustomChunkGenerator;
import org.bukkit.generator.BlockPopulator;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Regen_v1_16_R3 extends Regenerator<IChunkAccess, ProtoChunk, Chunk, Regen_v1_16_R3.ChunkStatusWrap> {

    private static final Field serverWorldsField;
    private static final Field worldPaperConfigField;
    private static final Field flatBedrockField;
    private static final Field generatorSettingBaseSupplierField;
    private static final Field generatorSettingFlatField;
    private static final Field delegateField;
    private static final Field chunkProviderField;

    //list of chunk stati in correct order without FULL
    private static final Map<ChunkStatus, Concurrency> chunkStati = new LinkedHashMap<>();

    static {
        chunkStati.put(ChunkStatus.EMPTY, Concurrency.FULL);            // radius -1, does nothing
        chunkStati.put(ChunkStatus.STRUCTURE_STARTS, Concurrency.NONE); // uses unsynchronized maps
        chunkStati.put(
                ChunkStatus.STRUCTURE_REFERENCES,
                Concurrency.FULL
        );                                                              // radius 8, but no writes to other chunks, only current chunk
        chunkStati.put(ChunkStatus.BIOMES, Concurrency.FULL);           // radius 0
        chunkStati.put(ChunkStatus.NOISE, Concurrency.RADIUS);          // radius 8
        chunkStati.put(ChunkStatus.SURFACE, Concurrency.NONE);          // surface: radius 0, requires NONE
        chunkStati.put(ChunkStatus.CARVERS, Concurrency.NONE);          // radius 0, but RADIUS and FULL change results
        chunkStati.put(ChunkStatus.LIQUID_CARVERS, Concurrency.NONE);   // radius 0, but RADIUS and FULL change results
        chunkStati.put(ChunkStatus.FEATURES, Concurrency.NONE);         // uses unsynchronized maps
        chunkStati.put(
                ChunkStatus.LIGHT,
                Concurrency.FULL
        );                                                              // radius 1, but no writes to other chunks, only current chunk
        chunkStati.put(ChunkStatus.SPAWN, Concurrency.FULL);            // radius 0
        chunkStati.put(ChunkStatus.HEIGHTMAPS, Concurrency.FULL);       // radius 0

        try {
            serverWorldsField = CraftServer.class.getDeclaredField("worlds");
            serverWorldsField.setAccessible(true);

            Field tmpPaperConfigField;
            Field tmpFlatBedrockField;
            try { //only present on paper
                tmpPaperConfigField = World.class.getDeclaredField("paperConfig");
                tmpPaperConfigField.setAccessible(true);

                tmpFlatBedrockField = tmpPaperConfigField.getType().getDeclaredField("generateFlatBedrock");
                tmpFlatBedrockField.setAccessible(true);
            } catch (Exception e) {
                tmpPaperConfigField = null;
                tmpFlatBedrockField = null;
            }
            worldPaperConfigField = tmpPaperConfigField;
            flatBedrockField = tmpFlatBedrockField;

            generatorSettingBaseSupplierField = ChunkGeneratorAbstract.class.getDeclaredField("h");
            generatorSettingBaseSupplierField.setAccessible(true);

            generatorSettingFlatField = ChunkProviderFlat.class.getDeclaredField("e");
            generatorSettingFlatField.setAccessible(true);

            delegateField = CustomChunkGenerator.class.getDeclaredField("delegate");
            delegateField.setAccessible(true);

            chunkProviderField = WorldServer.class.getDeclaredField("chunkProvider");
            chunkProviderField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //runtime
    private WorldServer originalNMSWorld;
    private ChunkProviderServer originalChunkProvider;
    private WorldServer freshNMSWorld;
    private ChunkProviderServer freshChunkProvider;
    private Convertable.ConversionSession session;
    private DefinedStructureManager structureManager;
    private LightEngineThreaded lightEngine;
    private ChunkGenerator generator;

    private Path tempDir;

    private boolean generateFlatBedrock = false;

    public Regen_v1_16_R3(org.bukkit.World originalBukkitWorld, Region region, Extent target, RegenOptions options) {
        super(originalBukkitWorld, region, target, options);
    }

    @Override
    protected boolean prepare() {
        this.originalNMSWorld = ((CraftWorld) originalBukkitWorld).getHandle();
        originalChunkProvider = originalNMSWorld.getChunkProvider();
        if (!(originalChunkProvider instanceof ChunkProviderServer)) {
            return false;
        }

        //flat bedrock? (only on paper)
        if (worldPaperConfigField != null) {
            try {
                generateFlatBedrock = flatBedrockField.getBoolean(worldPaperConfigField.get(originalNMSWorld));
            } catch (Exception ignored) {
            }
        }

        seed = options.getSeed().orElse(originalNMSWorld.getSeed());
        chunkStati.forEach((s, c) -> super.chunkStati.put(new ChunkStatusWrap(s), c));

        return true;
    }

    @Override
    protected boolean initNewWorld() throws Exception {
        //world folder
        tempDir = java.nio.file.Files.createTempDirectory("WorldEditWorldGen");

        //prepare for world init (see upstream implementation for reference)
        org.bukkit.World.Environment env = originalBukkitWorld.getEnvironment();
        org.bukkit.generator.ChunkGenerator gen = originalBukkitWorld.getGenerator();
        Convertable convertable = Convertable.a(tempDir);
        ResourceKey<WorldDimension> worldDimKey = getWorldDimKey(env);
        session = convertable.c("worldeditregentempworld", worldDimKey);
        WorldDataServer originalWorldData = originalNMSWorld.worldDataServer;

        MinecraftServer server = originalNMSWorld.getServer().getServer();
        WorldDataServer levelProperties = (WorldDataServer) server.getSaveData();
        RegistryReadOps<NBTBase> nbtRegOps = RegistryReadOps.a(
                DynamicOpsNBT.a,
                server.dataPackResources.h(),
                IRegistryCustom.b()
        );
        GeneratorSettings newOpts = GeneratorSettings.a
                .encodeStart(nbtRegOps, levelProperties.getGeneratorSettings())
                .flatMap(tag -> GeneratorSettings.a.parse(this.recursivelySetSeed(
                        new Dynamic<>(nbtRegOps, tag),
                        seed,
                        new HashSet<>()
                )))
                .result()
                .orElseThrow(() -> new IllegalStateException("Unable to map GeneratorOptions"));
        WorldSettings newWorldSettings = new WorldSettings(
                "worldeditregentempworld",
                originalWorldData.b.getGameType(),
                originalWorldData.b.hardcore,
                originalWorldData.b.getDifficulty(),
                originalWorldData.b.e(),
                originalWorldData.b.getGameRules(),
                originalWorldData.b.g()
        );
        WorldDataServer newWorldData = new WorldDataServer(newWorldSettings, newOpts, Lifecycle.stable());

        //init world
        freshNMSWorld = Fawe.get().getQueueHandler().sync((Supplier<WorldServer>) () -> new WorldServer(
                server,
                server.executorService,
                session,
                newWorldData,
                originalNMSWorld.getDimensionKey(),
                originalNMSWorld.getDimensionManager(),
                new RegenNoOpWorldLoadListener(),
                // placeholder. Required for new ChunkProviderServer, but we create and then set it later
                newOpts.d().a(worldDimKey).c(),
                originalNMSWorld.isDebugWorld(),
                seed,
                ImmutableList.of(),
                false,
                env,
                gen
        ) {
            private final BiomeBase singleBiome = options.hasBiomeType() ? RegistryGeneration.WORLDGEN_BIOME.get(MinecraftKey.a(
                    options.getBiomeType().getId())) : null;

            @Override
            public void doTick(BooleanSupplier booleansupplier) { //no ticking
            }

            @Override
            public BiomeBase a(int i, int j, int k) {
                if (options.hasBiomeType()) {
                    return singleBiome;
                }
                return Regen_v1_16_R3.this.generator.getWorldChunkManager().getBiome(i, j, k);
            }
        }).get();
        freshNMSWorld.savingDisabled = true;
        removeWorldFromWorldsMap();
        newWorldData.checkName(originalNMSWorld.worldDataServer.getName()); //rename to original world name
        if (worldPaperConfigField != null) {
            worldPaperConfigField.set(freshNMSWorld, originalNMSWorld.paperConfig);
        }

        //generator
        if (originalChunkProvider.getChunkGenerator() instanceof ChunkProviderFlat) {
            GeneratorSettingsFlat generatorSettingFlat = (GeneratorSettingsFlat) generatorSettingFlatField.get(
                    originalChunkProvider.getChunkGenerator());
            generator = new ChunkProviderFlat(generatorSettingFlat);
        } else if (originalChunkProvider.getChunkGenerator() instanceof ChunkGeneratorAbstract) {
            Supplier<GeneratorSettingBase> generatorSettingBaseSupplier = (Supplier<GeneratorSettingBase>) generatorSettingBaseSupplierField
                    .get(originalChunkProvider.getChunkGenerator());
            WorldChunkManager chunkManager = originalChunkProvider.getChunkGenerator().getWorldChunkManager();
            if (chunkManager instanceof WorldChunkManagerOverworld) {
                chunkManager = fastOverWorldChunkManager(chunkManager);
            }
            generator = new ChunkGeneratorAbstract(chunkManager, seed, generatorSettingBaseSupplier);
        } else if (originalChunkProvider.getChunkGenerator() instanceof CustomChunkGenerator) {
            generator = (ChunkGenerator) delegateField.get(originalChunkProvider.getChunkGenerator());
        } else {
            System.out.println("Unsupported generator type " + originalChunkProvider.getChunkGenerator().getClass().getName());
            return false;
        }
        if (gen != null) {
            generator = new CustomChunkGenerator(freshNMSWorld, generator, gen);
            generateConcurrent = gen.isParallelCapable();
        }

        freshChunkProvider = new ChunkProviderServer(
                freshNMSWorld,
                session,
                server.getDataFixer(),
                server.getDefinedStructureManager(),
                server.executorService,
                generator,
                freshNMSWorld.spigotConfig.viewDistance,
                server.isSyncChunkWrites(),
                new RegenNoOpWorldLoadListener(),
                () -> server.E().getWorldPersistentData()
        ) {
            // redirect to our protoChunks list
            @Override
            public IChunkAccess getChunkAt(int x, int z, ChunkStatus chunkstatus, boolean flag) {
                return Regen_v1_16_R3.this.getChunkAt(x, z);
            }
        };
        chunkProviderField.set(freshNMSWorld, freshChunkProvider);

        //lets start then
        structureManager = server.getDefinedStructureManager();
        lightEngine = freshChunkProvider.getLightEngine();

        return true;
    }

    @Override
    protected void cleanup() {
        try {
            session.close();
        } catch (Exception e) {
        }

        //shutdown chunk provider
        try {
            Fawe.get().getQueueHandler().sync(() -> {
                try {
                    freshChunkProvider.close(false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
        }

        //remove world from server
        try {
            Fawe.get().getQueueHandler().sync(() -> {
                removeWorldFromWorldsMap();
            });
        } catch (Exception e) {
        }

        //delete directory
        try {
            SafeFiles.tryHardToDeleteDir(tempDir);
        } catch (Exception e) {
        }
    }

    @Override
    protected ProtoChunk createProtoChunk(int x, int z) {
        return new ProtoChunk(new ChunkCoordIntPair(x, z), ChunkConverter.a) {
            public boolean generateFlatBedrock() {
                return generateFlatBedrock;
            }

            // no one will ever see the entities!
            @Override
            public List<NBTTagCompound> y() {
                return Collections.emptyList();
            }
        };
    }

    @Override
    protected Chunk createChunk(ProtoChunk protoChunk) {
        return new Chunk(freshNMSWorld, protoChunk);
    }

    @Override
    protected ChunkStatusWrap getFullChunkStatus() {
        return new ChunkStatusWrap(ChunkStatus.FULL);
    }

    @Override
    protected List<BlockPopulator> getBlockPopulators() {
        return originalNMSWorld.getWorld().getPopulators();
    }

    @Override
    protected void populate(Chunk chunk, Random random, BlockPopulator pop) {
        TaskManager.IMP.task(() -> pop.populate(freshNMSWorld.getWorld(), random, chunk.bukkitChunk));
    }

    @Override
    protected IChunkCache<IChunkGet> initSourceQueueCache() {
        return (chunkX, chunkZ) -> new BukkitGetBlocks_1_16_5(freshNMSWorld, chunkX, chunkZ) {
            @Override
            public Chunk ensureLoaded(WorldServer nmsWorld, int x, int z) {
                return getChunkAt(x, z);
            }
        };
    }

    //util
    private void removeWorldFromWorldsMap() {
        Fawe.get().getQueueHandler().sync(() -> {
            try {
                Map<String, org.bukkit.World> map = (Map<String, org.bukkit.World>) serverWorldsField.get(Bukkit.getServer());
                map.remove("worldeditregentempworld");
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private ResourceKey<WorldDimension> getWorldDimKey(org.bukkit.World.Environment env) {
        switch (env) {
            case NETHER:
                return WorldDimension.THE_NETHER;
            case THE_END:
                return WorldDimension.THE_END;
            case NORMAL:
            default:
                return WorldDimension.OVERWORLD;
        }
    }

    private Dynamic<NBTBase> recursivelySetSeed(Dynamic<NBTBase> dynamic, long seed, Set<Dynamic<NBTBase>> seen) {
        return !seen.add(dynamic) ? dynamic : dynamic.updateMapValues((pair) -> {
            if (pair.getFirst().asString("").equals("seed")) {
                return pair.mapSecond((v) -> {
                    return v.createLong(seed);
                });
            } else {
                return ((Dynamic) pair.getSecond()).getValue() instanceof NBTTagCompound ? pair.mapSecond((v) -> {
                    return this.recursivelySetSeed((Dynamic) v, seed, seen);
                }) : pair;

            }
        });
    }

    private WorldChunkManager fastOverWorldChunkManager(WorldChunkManager chunkManager) throws Exception {
        Field legacyBiomeInitLayerField = WorldChunkManagerOverworld.class.getDeclaredField("i");
        legacyBiomeInitLayerField.setAccessible(true);
        Field largeBiomesField = WorldChunkManagerOverworld.class.getDeclaredField("j");
        largeBiomesField.setAccessible(true);
        Field biomeRegistryField = WorldChunkManagerOverworld.class.getDeclaredField("k");
        biomeRegistryField.setAccessible(true);
        Field areaLazyField = GenLayer.class.getDeclaredField("b");
        areaLazyField.setAccessible(true);
        Method initAreaFactoryMethod = GenLayers.class.getDeclaredMethod(
                "a",
                boolean.class,
                int.class,
                int.class,
                LongFunction.class
        );
        initAreaFactoryMethod.setAccessible(true);

        //init new WorldChunkManagerOverworld
        boolean legacyBiomeInitLayer = legacyBiomeInitLayerField.getBoolean(chunkManager);
        boolean largebiomes = largeBiomesField.getBoolean(chunkManager);
        IRegistry<BiomeBase> biomeRegistrynms = (IRegistry<BiomeBase>) biomeRegistryField.get(chunkManager);
        IRegistry<BiomeBase> biomeRegistry;
        if (options.hasBiomeType()) {
            BiomeBase biome = RegistryGeneration.WORLDGEN_BIOME.get(MinecraftKey.a(options.getBiomeType().getId()));
            biomeRegistry = new RegistryMaterials<>(ResourceKey.a(new MinecraftKey("fawe_biomes")), Lifecycle.experimental());
            ((RegistryMaterials) biomeRegistry).a(
                    0,
                    RegistryGeneration.WORLDGEN_BIOME.c(biome).get(),
                    biome,
                    Lifecycle.experimental()
            );
        } else {
            biomeRegistry = biomeRegistrynms;
        }

        //replace genLayer
        AreaFactory<FastAreaLazy> factory = (AreaFactory<FastAreaLazy>) initAreaFactoryMethod.invoke(
                null,
                legacyBiomeInitLayer,
                largebiomes ? 6 : 4,
                4,
                (LongFunction) (salt -> new FastWorldGenContextArea(seed, salt))
        );
        chunkManager = new FastWorldChunkManagerOverworld(biomeRegistry, new FastGenLayer(factory));

        return chunkManager;
    }

    private static class FastWorldChunkManagerOverworld extends WorldChunkManager {

        private final IRegistry<BiomeBase> k;
        private final boolean isSingleRegistry;
        private final FastGenLayer genLayer;

        public FastWorldChunkManagerOverworld(
                IRegistry<BiomeBase> biomeRegistry,
                FastGenLayer genLayer
        ) {
            super(biomeRegistry.g().collect(Collectors.toList()));
            this.k = biomeRegistry;
            this.isSingleRegistry = biomeRegistry.d().size() == 1;
            this.genLayer = genLayer;
        }

        @Override
        protected Codec<? extends WorldChunkManager> a() {
            return WorldChunkManagerOverworld.e;
        }

        @Override
        public BiomeBase getBiome(int biomeX, int biomeY, int biomeZ) {
            if (this.isSingleRegistry) {
                return this.k.fromId(0);
            }
            return this.genLayer.a(this.k, biomeX, biomeZ);
        }

    }

    private static class FastWorldGenContextArea implements AreaContextTransformed<FastAreaLazy> {

        private final ConcurrentHashMap<Long, Integer> sharedAreaMap = new ConcurrentHashMap<>();
        private final NoiseGeneratorPerlin perlinNoise;
        private final long magicrandom;
        private final ConcurrentHashMap<Long, Long> map = new ConcurrentHashMap<>(); //needed for multithreaded generation

        public FastWorldGenContextArea(long seed, long lconst) {
            this.magicrandom = mix(seed, lconst);
            this.perlinNoise = new NoiseGeneratorPerlin(new Random(seed));
        }

        private static long mix(long seed, long salt) {
            long l = LinearCongruentialGenerator.a(salt, salt);
            l = LinearCongruentialGenerator.a(l, salt);
            l = LinearCongruentialGenerator.a(l, salt);
            long m = LinearCongruentialGenerator.a(seed, l);
            m = LinearCongruentialGenerator.a(m, l);
            m = LinearCongruentialGenerator.a(m, l);
            return m;
        }

        @Override
        public FastAreaLazy a(AreaTransformer8 var0) {
            return new FastAreaLazy(sharedAreaMap, var0);
        }

        @Override
        public FastAreaLazy a(AreaTransformer8 var0, FastAreaLazy parent) {
            return new FastAreaLazy(sharedAreaMap, var0);
        }

        @Override
        public FastAreaLazy a(AreaTransformer8 var0, FastAreaLazy firstParent, FastAreaLazy secondParent) {
            return new FastAreaLazy(sharedAreaMap, var0);
        }

        @Override
        public void a(long x, long z) {
            long l = this.magicrandom;
            l = LinearCongruentialGenerator.a(l, x);
            l = LinearCongruentialGenerator.a(l, z);
            l = LinearCongruentialGenerator.a(l, x);
            l = LinearCongruentialGenerator.a(l, z);
            this.map.put(Thread.currentThread().getId(), l);
        }

        @Override
        public int a(int y) {
            long tid = Thread.currentThread().getId();
            long e = this.map.computeIfAbsent(tid, i -> 0L);
            int mod = (int) Math.floorMod(e >> 24L, (long) y);
            this.map.put(tid, LinearCongruentialGenerator.a(e, this.magicrandom));
            return mod;
        }

        @Override
        public NoiseGeneratorPerlin b() {
            return this.perlinNoise;
        }

    }

    private static class FastGenLayer extends GenLayer {

        private final FastAreaLazy areaLazy;

        public FastGenLayer(AreaFactory<FastAreaLazy> factory) {
            super(() -> null);
            this.areaLazy = factory.make();
        }

        @Override
        public BiomeBase a(IRegistry<BiomeBase> registry, int x, int z) {
            ResourceKey<BiomeBase> key = BiomeRegistry.a(this.areaLazy.a(x, z));
            if (key == null) {
                return registry.a(BiomeRegistry.a(0));
            }
            BiomeBase biome = registry.a(key);
            if (biome == null) {
                return registry.a(BiomeRegistry.a(0));
            }
            return biome;
        }

    }

    private static class FastAreaLazy implements Area {

        private final AreaTransformer8 transformer;
        //ConcurrentHashMap is 50% faster that Long2IntLinkedOpenHashMap in a synchronized context
        //using a map for each thread worsens the performance significantly due to cache misses (factor 5)
        private final ConcurrentHashMap<Long, Integer> sharedMap;

        public FastAreaLazy(ConcurrentHashMap<Long, Integer> sharedMap, AreaTransformer8 transformer) {
            this.sharedMap = sharedMap;
            this.transformer = transformer;
        }

        @Override
        public int a(int x, int z) {
            long zx = ChunkCoordIntPair.pair(x, z);
            return this.sharedMap.computeIfAbsent(zx, i -> this.transformer.apply(x, z));
        }

    }

    private static class RegenNoOpWorldLoadListener implements WorldLoadListener {

        private RegenNoOpWorldLoadListener() {
        }

        @Override
        public void a(ChunkCoordIntPair chunkCoordIntPair) {
        }

        @Override
        public void a(ChunkCoordIntPair chunkCoordIntPair, @Nullable ChunkStatus chunkStatus) {
        }

        @Override
        public void b() {
        }

        @Override
        public void setChunkRadius(int i) {
        }

    }

    protected class ChunkStatusWrap extends ChunkStatusWrapper<IChunkAccess> {

        private final ChunkStatus chunkStatus;

        public ChunkStatusWrap(ChunkStatus chunkStatus) {
            this.chunkStatus = chunkStatus;
        }

        @Override
        public int requiredNeighborChunkRadius() {
            return chunkStatus.f();
        }

        @Override
        public String name() {
            return chunkStatus.d();
        }

        @Override
        public CompletableFuture<?> processChunk(Long xz, List<IChunkAccess> accessibleChunks) {
            return chunkStatus.a(
                    freshNMSWorld,
                    generator,
                    structureManager,
                    lightEngine,
                    c -> CompletableFuture.completedFuture(Either.left(c)),
                    accessibleChunks
            );
        }

    }

}
