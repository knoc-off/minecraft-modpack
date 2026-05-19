package dev.paintcraft.client;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers a PBR texture loader for PBRDynamicTexture with Iris/NeOculus
 * if the shader mod is present. Uses reflection to avoid a hard dependency.
 *
 * The mechanism: Iris maps texture GL IDs to PBR companions via
 * PBRTextureLoaderRegistry, keyed by exact class. We register a loader
 * for PBRDynamicTexture that returns the companion DynamicTextures
 * PaintCraft already manages in the atlas.
 */
public final class IrisCompat {

    private static final Logger LOG = LoggerFactory.getLogger("PaintCraft");
    private static boolean registered = false;

    private IrisCompat() {}

    /**
     * Call from client mod init. Safe to call even if Iris/NeOculus is not installed.
     */
    public static void tryRegister() {
        if (registered) return;

        boolean hasIris = ModList.get().isLoaded("oculus")
            || ModList.get().isLoaded("neoculus")
            || ModList.get().isLoaded("iris");

        if (!hasIris) {
            LOG.info("No Iris/NeOculus detected, PBR companion textures will not be bound by shaders");
            return;
        }

        try {
            registerLoader();
            registered = true;
            LOG.info("Registered PBR texture loader with Iris/NeOculus");
        } catch (Throwable e) {
            LOG.warn("Failed to register PBR loader with Iris/NeOculus (API may have changed)", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerLoader() throws Exception {
        // Access the registry via reflection to avoid compile-time dependency
        Class<?> registryClass = Class.forName(
            "net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry");
        // Could also be in texture.pbr.loader for NeOculus
        Object instance;
        try {
            instance = registryClass.getField("INSTANCE").get(null);
        } catch (NoSuchFieldException e) {
            registryClass = Class.forName(
                "net.irisshaders.iris.texture.pbr.loader.PBRTextureLoaderRegistry");
            instance = registryClass.getField("INSTANCE").get(null);
        }

        var registerMethod = registryClass.getMethod("register", Class.class,
            Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoader"));

        // The loader lambda: given a PBRDynamicTexture, return its companions
        // We need to create an instance implementing the PBRTextureLoader interface
        Class<?> loaderInterface;
        try {
            loaderInterface = Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoader");
        } catch (ClassNotFoundException e) {
            loaderInterface = Class.forName("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader");
        }

        Class<?> consumerInterface;
        try {
            consumerInterface = Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoader$PBRTextureConsumer");
        } catch (ClassNotFoundException e) {
            consumerInterface = Class.forName("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader$PBRTextureConsumer");
        }

        // Use a dynamic proxy to implement the loader interface
        final Class<?> finalConsumerInterface = consumerInterface;
        Object loader = java.lang.reflect.Proxy.newProxyInstance(
            loaderInterface.getClassLoader(),
            new Class<?>[]{ loaderInterface },
            (proxy, method, args) -> {
                if ("load".equals(method.getName()) && args.length == 3) {
                    PBRDynamicTexture texture = (PBRDynamicTexture) args[0];
                    Object consumer = args[2];

                    if (texture.getNormalCompanion() != null) {
                        finalConsumerInterface.getMethod("acceptNormalTexture",
                            Class.forName("net.minecraft.client.renderer.texture.AbstractTexture"))
                            .invoke(consumer, texture.getNormalCompanion());
                    }
                    if (texture.getSpecularCompanion() != null) {
                        finalConsumerInterface.getMethod("acceptSpecularTexture",
                            Class.forName("net.minecraft.client.renderer.texture.AbstractTexture"))
                            .invoke(consumer, texture.getSpecularCompanion());
                    }
                }
                return null;
            }
        );

        // Also try the NeOculus package path
        try {
            registerMethod.invoke(instance, PBRDynamicTexture.class, loader);
        } catch (Exception e) {
            var registerMethod2 = registryClass.getMethod("register", Class.class,
                Class.forName("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader"));
            registerMethod2.invoke(instance, PBRDynamicTexture.class, loader);
        }
    }
}
