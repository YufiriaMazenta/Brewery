package com.dre.brewery;

import com.dre.brewery.api.events.IngedientAddEvent;
import com.dre.brewery.recipe.BCauldronRecipe;
import com.dre.brewery.recipe.RecipeItem;
import com.dre.brewery.utility.BUtil;
import com.dre.brewery.utility.LegacyUtil;
import org.bukkit.Effect;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class BCauldron {
	public static final byte EMPTY = 0, SOME = 1, FULL = 2;
	private static Set<UUID> plInteracted = new HashSet<>();
	public static CopyOnWriteArrayList<BCauldron> bcauldrons = new CopyOnWriteArrayList<>(); // TODO find best Collection

	private BIngredients ingredients = new BIngredients();
	private final Block block;
	private int state = 1;
	private boolean changed = false;

	public BCauldron(Block block) {
		this.block = block;
		bcauldrons.add(this);
	}

	// loading from file
	public BCauldron(Block block, BIngredients ingredients, int state) {
		this.block = block;
		this.state = state;
		this.ingredients = ingredients;
		bcauldrons.add(this);
	}

	public void onUpdate() {
		// Check if fire still alive
		if (!BUtil.isChunkLoaded(block) || LegacyUtil.isFireForCauldron(block.getRelative(BlockFace.DOWN))) {
			// add a minute to cooking time
			state++;
			if (changed) {
				ingredients = ingredients.copy();
				changed = false;
			}
		}
	}

	// add an ingredient to the cauldron
	public void add(ItemStack ingredient, RecipeItem rItem) {
		if (ingredient == null || ingredient.getType() == Material.AIR) return;
		if (changed) {
			ingredients = ingredients.copy();
			changed = false;
		}

		ingredients.add(ingredient, rItem);
		block.getWorld().playEffect(block.getLocation(), Effect.EXTINGUISH, 0);
		if (state > 1) {
			state--;
		}
	}

	// get cauldron by Block
	public static BCauldron get(Block block) {
		for (BCauldron bcauldron : bcauldrons) {
			if (bcauldron.block.equals(block)) {
				return bcauldron;
			}
		}
		return null;
	}

	// get cauldron from block and add given ingredient
	// Calls the IngredientAddEvent and may be cancelled or changed
	public static boolean ingredientAdd(Block block, ItemStack ingredient, Player player) {
		// if not empty
		if (LegacyUtil.getFillLevel(block) != EMPTY) {
			BCauldron bcauldron = get(block);
			if (bcauldron == null) {
				bcauldron = new BCauldron(block);
			}

			if (!BCauldronRecipe.acceptedMaterials.contains(ingredient.getType()) && !ingredient.hasItemMeta()) {
				// Extremely fast way to check for most items
				return false;
			}
			// If the Item is on the list, or customized, we have to do more checks
			RecipeItem rItem = RecipeItem.getMatchingRecipeItem(ingredient, false);
			if (rItem == null) {
				return false;
			}

			IngedientAddEvent event = new IngedientAddEvent(player, block, bcauldron, ingredient.clone(), rItem);
			P.p.getServer().getPluginManager().callEvent(event);
			if (!event.isCancelled()) {
				bcauldron.add(event.getIngredient(), event.getRecipeItem());
				return event.willTakeItem();
			} else {
				return false;
			}
		}
		return false;
	}

	// fills players bottle with cooked brew
	public boolean fill(Player player, Block block) {
		if (!player.hasPermission("brewery.cauldron.fill")) {
			P.p.msg(player, P.p.languageReader.get("Perms_NoCauldronFill"));
			return true;
		}
		ItemStack potion = ingredients.cook(state);
		if (potion == null) return false;

		if (P.use1_13) {
			BlockData data = block.getBlockData();
			Levelled cauldron = ((Levelled) data);
			if (cauldron.getLevel() <= 0) {
				bcauldrons.remove(this);
				return false;
			}
			cauldron.setLevel(cauldron.getLevel() - 1);
			// Update the new Level to the Block
			// We have to use the BlockData variable "data" here instead of the casted "cauldron"
			// otherwise < 1.13 crashes on plugin load for not finding the BlockData Class
			block.setBlockData(data);

			if (cauldron.getLevel() <= 0) {
				bcauldrons.remove(this);
			} else {
				changed = true;
			}

		} else {
			byte data = block.getData();
			if (data > 3) {
				data = 3;
			} else if (data <= 0) {
				bcauldrons.remove(this);
				return false;
			}
			data -= 1;
			LegacyUtil.setData(block, data);

			if (data == 0) {
				bcauldrons.remove(this);
			} else {
				changed = true;
			}
		}
		// Bukkit Bug, inventory not updating while in event so this
		// will delay the give
		// but could also just use deprecated updateInventory()
		giveItem(player, potion);
		// player.getInventory().addItem(potion);
		// player.getInventory().updateInventory();
		return true;
	}

	// prints the current cooking time to the player
	public static void printTime(Player player, Block block) {
		if (!player.hasPermission("brewery.cauldron.time")) {
			P.p.msg(player, P.p.languageReader.get("Error_NoPermissions"));
			return;
		}
		BCauldron bcauldron = get(block);
		if (bcauldron != null) {
			if (bcauldron.state > 1) {
				P.p.msg(player, P.p.languageReader.get("Player_CauldronInfo1", "" + bcauldron.state));
			} else {
				P.p.msg(player, P.p.languageReader.get("Player_CauldronInfo2"));
			}
		}
	}

	public static void clickCauldron(PlayerInteractEvent event) {
		Material materialInHand = event.getMaterial();
		ItemStack item = event.getItem();
		Player player = event.getPlayer();
		Block clickedBlock = event.getClickedBlock();
		assert clickedBlock != null;

		if (materialInHand == null || materialInHand == Material.AIR || materialInHand == Material.BUCKET) {
			return;

		} else if (materialInHand == LegacyUtil.CLOCK) {
			printTime(player, clickedBlock);
			return;

			// fill a glass bottle with potion
		} else if (materialInHand == Material.GLASS_BOTTLE) {
			assert item != null;
			if (player.getInventory().firstEmpty() != -1 || item.getAmount() == 1) {
				BCauldron bcauldron = get(clickedBlock);
				if (bcauldron != null) {
					if (bcauldron.fill(player, clickedBlock)) {
						event.setCancelled(true);
						if (player.hasPermission("brewery.cauldron.fill")) {
							if (item.getAmount() > 1) {
								item.setAmount(item.getAmount() - 1);
							} else {
								setItemInHand(event, Material.AIR, false);
							}
						}
					}
				}
			} else {
				event.setCancelled(true);
			}
			return;

			// reset cauldron when refilling to prevent unlimited source of potions
		} else if (materialInHand == Material.WATER_BUCKET) {
			if (!P.use1_9) {
				// We catch >=1.9 cases in the Cauldron Listener
				if (LegacyUtil.getFillLevel(clickedBlock) == 1) {
					// will only remove when existing
					BCauldron.remove(clickedBlock);
				}
			}
			return;
		}

		// Check if fire alive below cauldron when adding ingredients
		Block down = clickedBlock.getRelative(BlockFace.DOWN);
		if (LegacyUtil.isFireForCauldron(down)) {

			event.setCancelled(true);
			boolean handSwap = false;

			// Interact event is called twice!!!?? in 1.9, once for each hand.
			// Certain Items in Hand cause one of them to be cancelled or not called at all sometimes.
			// We mark if a player had the event for the main hand
			// If not, we handle the main hand in the event for the off hand
			if (P.use1_9) {
				if (event.getHand() == EquipmentSlot.HAND) {
					final UUID id = player.getUniqueId();
					plInteracted.add(id);
					P.p.getServer().getScheduler().runTask(P.p, () -> plInteracted.remove(id));
				} else if (event.getHand() == EquipmentSlot.OFF_HAND) {
					if (!plInteracted.remove(player.getUniqueId())) {
						item = player.getInventory().getItemInMainHand();
						if (item != null && item.getType() != Material.AIR) {
							materialInHand = item.getType();
							handSwap = true;
						} else {
							item = event.getItem();
						}
					}
				}
			}
			if (item == null) return;

			if (!player.hasPermission("brewery.cauldron.insert")) {
				P.p.msg(player, P.p.languageReader.get("Perms_NoCauldronInsert"));
				return;
			}
			if (ingredientAdd(clickedBlock, item, player)) {
				boolean isBucket = item.getType().equals(Material.WATER_BUCKET)
					|| item.getType().equals(Material.LAVA_BUCKET)
					|| item.getType().equals(Material.MILK_BUCKET);
				if (item.getAmount() > 1) {
					item.setAmount(item.getAmount() - 1);

					if (isBucket) {
						giveItem(player, new ItemStack(Material.BUCKET));
					}
				} else {
					if (isBucket) {
						setItemInHand(event, Material.BUCKET, handSwap);
					} else {
						setItemInHand(event, Material.AIR, handSwap);
					}
				}
			}
		}
	}

	@SuppressWarnings("deprecation")
	public static void setItemInHand(PlayerInteractEvent event, Material mat, boolean swapped) {
		if (P.use1_9) {
			if ((event.getHand() == EquipmentSlot.OFF_HAND) != swapped) {
				event.getPlayer().getInventory().setItemInOffHand(new ItemStack(mat));
			} else {
				event.getPlayer().getInventory().setItemInMainHand(new ItemStack(mat));
			}
		} else {
			event.getPlayer().setItemInHand(new ItemStack(mat));
		}
	}

	// reset to normal cauldron
	public static boolean remove(Block block) {
		if (LegacyUtil.getFillLevel(block) != EMPTY) {
			BCauldron bcauldron = get(block);
			if (bcauldron != null) {
				bcauldrons.remove(bcauldron);
				return true;
			}
		}
		return false;
	}

	// unloads cauldrons that are in a unloading world
	// as they were written to file just before, this is safe to do
	public static void onUnload(String name) {
		for (BCauldron bcauldron : bcauldrons) {
			if (bcauldron.block.getWorld().getName().equals(name)) {
				bcauldrons.remove(bcauldron);
			}
		}
	}

	public static void save(ConfigurationSection config, ConfigurationSection oldData) {
		BUtil.createWorldSections(config);

		if (!bcauldrons.isEmpty()) {
			int id = 0;
			for (BCauldron cauldron : bcauldrons) {
				String worldName = cauldron.block.getWorld().getName();
				String prefix;

				if (worldName.startsWith("DXL_")) {
					prefix = BUtil.getDxlName(worldName) + "." + id;
				} else {
					prefix = cauldron.block.getWorld().getUID().toString() + "." + id;
				}

				config.set(prefix + ".block", cauldron.block.getX() + "/" + cauldron.block.getY() + "/" + cauldron.block.getZ());
				if (cauldron.state != 1) {
					config.set(prefix + ".state", cauldron.state);
				}
				config.set(prefix + ".ingredients", cauldron.ingredients.serializeIngredients());
				id++;
			}
		}
		// copy cauldrons that are not loaded
		if (oldData != null){
			for (String uuid : oldData.getKeys(false)) {
				if (!config.contains(uuid)) {
					config.set(uuid, oldData.get(uuid));
				}
			}
		}
	}

	// bukkit bug not updating the inventory while executing event, have to
	// schedule the give
	public static void giveItem(final Player player, final ItemStack item) {
		P.p.getServer().getScheduler().runTaskLater(P.p, () -> player.getInventory().addItem(item), 1L);
	}

}
