package com.mcctimer.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

public class GiveAllCosmeticsCommand implements Command {
    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        new Thread(() -> {
            try {
                // 1. Construct the fake LoginResponse with all cosmetics + Lunar+
                // We use reflection since the protobuf classes might not be available directly in our classloader 
                // but they are available in the game's classloader.
                ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
                if (gameLoader == null) {
                    CommandHandler.sendClientMessage("\u00A7c[LC] Error: No game classloader found.");
                    return;
                }

                Class<?> loginResponseClass;
                try {
                    loginResponseClass = Class.forName("com.lunarclient.websocket.cosmetic.v2.LoginResponse", false, gameLoader);
                } catch (ClassNotFoundException e) {
                    CommandHandler.sendClientMessage("\u00A7c[LC] Error: Could not find LoginResponse class.");
                    return;
                }

                Class<?> builderClass;
                try {
                    builderClass = Class.forName("com.lunarclient.websocket.cosmetic.v2.LoginResponse$Builder", false, gameLoader);
                } catch (ClassNotFoundException e) {
                    try {
                        // Sometimes it's obfuscated differently or just outer.Builder
                        builderClass = Class.forName("com.lunarclient.websocket.cosmetic.v2.LoginResponse$Builder", false, gameLoader);
                    } catch (Throwable t) {
                        builderClass = null; // We'll try dynamic method lookup on the base class
                    }
                }
                
                // Get newBuilder() on LoginResponse
                Method newBuilderMethod = loginResponseClass.getMethod("newBuilder");
                Object builder = newBuilderMethod.invoke(null);
                
                // setHasAllCosmeticsFlag(true)
                Method setHasAllCosmeticsFlagMethod = builder.getClass().getMethod("setHasAllCosmeticsFlag", boolean.class);
                setHasAllCosmeticsFlagMethod.invoke(builder, true);
                
                // setPlusColor(Color.newBuilder().setColor(16711680).build())
                Class<?> colorClass = Class.forName("com.lunarclient.common.v1.Color", false, gameLoader);
                Object colorBuilder = colorClass.getMethod("newBuilder").invoke(null);
                colorBuilder.getClass().getMethod("setColor", int.class).invoke(colorBuilder, 0xFF5555); // Red
                Object colorObj = colorBuilder.getClass().getMethod("build").invoke(colorBuilder);
                
                Method setPlusColorMethod = builder.getClass().getMethod("setPlusColor", colorClass);
                setPlusColorMethod.invoke(builder, colorObj);
                
                // Fix: Include a valid empty Default Outfit to prevent NPEs when the Outfit UI opens,
                // and to allow saving/equipping cosmetics.
                Class<?> uuidClass = Class.forName("com.lunarclient.common.v1.Uuid", false, gameLoader);
                Object uuidBuilder = uuidClass.getMethod("newBuilder").invoke(null);
                java.util.UUID rnd = java.util.UUID.randomUUID();
                uuidBuilder.getClass().getMethod("setHigh64", long.class).invoke(uuidBuilder, rnd.getMostSignificantBits());
                uuidBuilder.getClass().getMethod("setLow64", long.class).invoke(uuidBuilder, rnd.getLeastSignificantBits());
                Object msgUuid = uuidBuilder.getClass().getMethod("build").invoke(uuidBuilder);

                Class<?> outfitClass = Class.forName("com.lunarclient.websocket.cosmetic.v2.Outfit", false, gameLoader);
                Object outfitBuilder = outfitClass.getMethod("newBuilder").invoke(null);
                outfitBuilder.getClass().getMethod("setId", uuidClass).invoke(outfitBuilder, msgUuid);
                outfitBuilder.getClass().getMethod("setName", String.class).invoke(outfitBuilder, "GiveAll Default");
                Object outfitMsg = outfitBuilder.getClass().getMethod("build").invoke(outfitBuilder);

                Class<?> treeClass = Class.forName("com.lunarclient.websocket.cosmetic.v2.OutfitTree", false, gameLoader);
                Object treeBuilder = treeClass.getMethod("newBuilder").invoke(null);
                treeBuilder.getClass().getMethod("setDefaultOutfitId", uuidClass).invoke(treeBuilder, msgUuid);
                Object treeMsg = treeBuilder.getClass().getMethod("build").invoke(treeBuilder);

                builder.getClass().getMethod("addOutfits", outfitClass).invoke(builder, outfitMsg);
                builder.getClass().getMethod("setOutfitTree", treeClass).invoke(builder, treeMsg);

                // Populate Lunar+ colors so the color picker isn't empty
                java.util.List<Object> colors = new java.util.ArrayList<>();
                for (int colorInt : new int[] { 16733525, 5592575, 5635840, 16711680, 65280, 255, 16777215, 0 }) {
                    Object cBuilder = colorClass.getMethod("newBuilder").invoke(null);
                    cBuilder.getClass().getMethod("setColor", int.class).invoke(cBuilder, colorInt);
                    colors.add(cBuilder.getClass().getMethod("build").invoke(cBuilder));
                }
                builder.getClass().getMethod("addAllAvailableLunarPlusColors", Iterable.class).invoke(builder, colors);

                // Build the final mock LoginResponse packet
                Object fakeLoginResponse = builder.getClass().getMethod("build").invoke(builder);

                // 2. Discover the LunarClient singletons and their managers
                Class<?>[] allClasses = MCCTimerAgent.instrumentation.getAllLoadedClasses();
                int injectedCount = 0;

                for (Class<?> c : allClasses) {
                    if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.")) continue;
                    Object singletonInstance = null;
                    try {
                        for (Field f : c.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) {
                                f.setAccessible(true);
                                singletonInstance = f.get(null);
                                break;
                            }
                        }
                    } catch (Throwable t) {}
                    if (singletonInstance == null) continue;

                    // 3. Iterate fields of this singleton to find the cosmetic and plus managers
                    for (Field managerField : singletonInstance.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(managerField.getModifiers())) continue;
                        
                        Class<?> fieldType = managerField.getType();
                        if (fieldType.isPrimitive() || fieldType == String.class || !fieldType.getName().startsWith("com.moonsworth.lunar.")) continue;
                        
                        try {
                            managerField.setAccessible(true);
                            Object managerInstance = managerField.get(singletonInstance);
                            if (managerInstance == null) continue;
                            
                            // Check methods of the manager instance
                            boolean injected = false;
                            Class<?> managerClass = managerInstance.getClass();
                            while (managerClass != null && managerClass != Object.class && !injected) {
                                for (Method m : managerClass.getDeclaredMethods()) {
                                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == loginResponseClass) {
                                        // BINGO! A method that accepts the LoginResponse
                                        m.setAccessible(true);
                                        m.invoke(managerInstance, fakeLoginResponse);
                                        injectedCount++;
                                        injected = true;
                                    }
                                }
                                managerClass = managerClass.getSuperclass();
                            }
                        } catch (Throwable t) {
                        }
                    }
                }

                if (injectedCount > 0) {
                    CommandHandler.sendClientMessage("\u00A7a[LC] Successfully injected unlocks to " + injectedCount + " manager(s)!");
                } else {
                    CommandHandler.sendClientMessage("\u00A7c[LC] Error: Found no managers accepting LoginResponse.");
                }

            } catch (Throwable t) {
                t.printStackTrace();
                CommandHandler.sendClientMessage("\u00A7c[LC] Fatal error parsing reflection: " + t.getMessage());
            }
        }).start();

        return true;
    }
}
