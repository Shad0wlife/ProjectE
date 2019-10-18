package moze_intel.projecte.gameObjs.items;

import com.google.common.collect.ImmutableSet;
import moze_intel.projecte.PECore;
import moze_intel.projecte.api.item.IItemCharge;
import moze_intel.projecte.api.item.IModeChanger;
import moze_intel.projecte.api.item.IPedestalItem;
import moze_intel.projecte.config.ProjectEConfig;
import moze_intel.projecte.gameObjs.tiles.DMPedestalTile;
import moze_intel.projecte.utils.EMCHelper;
import moze_intel.projecte.utils.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.FlowingFluidBlock;
import net.minecraft.block.IGrowable;
import net.minecraft.block.BlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.Tag;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.IPlantable;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

public class TimeWatch extends ItemPE implements IModeChanger, IPedestalItem, IItemCharge
{
	private static Set<TileEntityType<?>> internalBlacklist = Collections.emptySet();
	private static final Tag<Block> BLOCK_BLACKLIST_TAG = new BlockTags.Wrapper(new ResourceLocation(PECore.MODID, "time_watch_blacklist"));

	public TimeWatch(Properties props)
	{
		super(props);
		this.addPropertyOverride(ACTIVE_NAME, ACTIVE_GETTER);
	}

	@Nonnull
	@Override
	public ActionResult<ItemStack> onItemRightClick(World world, PlayerEntity player, @Nonnull Hand hand)
	{
		ItemStack stack = player.getHeldItem(hand);
		if (!world.isRemote)
		{
			if (!ProjectEConfig.items.enableTimeWatch.get())
			{
				player.sendMessage(new TranslationTextComponent("pe.timewatch.disabled"));
				return ActionResult.newResult(ActionResultType.FAIL, stack);
			}

			byte current = getTimeBoost(stack);

			setTimeBoost(stack, (byte) (current == 2 ? 0 : current + 1));

			player.sendMessage(new TranslationTextComponent("pe.timewatch.mode_switch", new TranslationTextComponent(getTimeName(stack)).getUnformattedComponentText()));
		}

		return ActionResult.newResult(ActionResultType.SUCCESS, stack);
	}

	@Override
	public void inventoryTick(@Nonnull ItemStack stack, @Nonnull World world, @Nonnull Entity entity, int invSlot, boolean isHeld)
	{
		super.inventoryTick(stack, world, entity, invSlot, isHeld);
		
		if (!(entity instanceof PlayerEntity) || invSlot > 8)
		{
			return;
		}

		if (!ProjectEConfig.items.enableTimeWatch.get())
		{
			return;
		}

		byte timeControl = getTimeBoost(stack);

		if (world.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)) {
			if (timeControl == 1)
            {
				world.setDayTime(Math.min(world.getDayTime() + ((getCharge(stack) + 1) * 4), Long.MAX_VALUE));
            }
            else if (timeControl == 2)
            {
                if (world.getDayTime() - ((getCharge(stack) + 1) * 4) < 0)
                {
                    world.setDayTime(0);
                }
                else
                {
                    world.setDayTime((world.getDayTime() - ((getCharge(stack) + 1) * 4)));
                }
            }
		}

        if (world.isRemote || !stack.getOrCreateTag().getBoolean(TAG_ACTIVE))
		{
			return;
		}

		PlayerEntity player = (PlayerEntity) entity;
		long reqEmc = EMCHelper.removeFractionalEMC(stack, getEmcPerTick(this.getCharge(stack)));

		if (!consumeFuel(player, stack, reqEmc, true))
		{
			return;
		}
		
		int charge = this.getCharge(stack);
		int bonusTicks;
		float mobSlowdown;
		
		if (charge == 0)
		{
			bonusTicks = 8;
			mobSlowdown = 0.25F;
		}
		else if (charge == 1)
		{
			bonusTicks = 12;
			mobSlowdown = 0.16F;
		}
		else
		{
			bonusTicks = 16;
			mobSlowdown = 0.12F;
		}
			
		AxisAlignedBB bBox = player.getBoundingBox().grow(8);

		speedUpTileEntities(world, bonusTicks, bBox);
		speedUpRandomTicks(world, bonusTicks, bBox);
		slowMobs(world, bBox, mobSlowdown);
	}

	private void slowMobs(World world, AxisAlignedBB bBox, double mobSlowdown)
	{
		if (bBox == null) // Sanity check for chunk unload weirdness
		{
			return;
		}
		for (MobEntity ent : world.getEntitiesWithinAABB(MobEntity.class, bBox))
		{
			ent.setMotion(ent.getMotion().mul(mobSlowdown, 1, mobSlowdown));
		}
	}

	private void speedUpTileEntities(World world, int bonusTicks, AxisAlignedBB bBox)
	{
		if (bBox == null || bonusTicks == 0) // Sanity check the box for chunk unload weirdness
		{
			return;
		}

		Set<ResourceLocation> blacklist = ProjectEConfig.effects.timeWatchTEBlacklist.get().stream()
				.map(ResourceLocation::new)
				.collect(Collectors.toSet());
		List<TileEntity> list = WorldHelper.getTileEntitiesWithinAABB(world, bBox);
		for (int i = 0; i < bonusTicks; i++)
		{
			for (TileEntity tile : list)
			{
				if (!tile.isRemoved() && tile instanceof ITickableTileEntity
						&& !internalBlacklist.contains(tile.getType())
						&& !blacklist.contains(tile.getType().getRegistryName()))
				{
					((ITickableTileEntity) tile).tick();
				}
			}
		}
	}

	private void speedUpRandomTicks(World world, int bonusTicks, AxisAlignedBB bBox)
	{
		if (bBox == null || bonusTicks == 0) // Sanity check the box for chunk unload weirdness
		{
			return;
		}

		for (BlockPos pos : WorldHelper.getPositionsFromBox(bBox))
		{
			for (int i = 0; i < bonusTicks; i++)
			{
				BlockState state = world.getBlockState(pos);
				Block block = state.getBlock();
				if (state.ticksRandomly()
						&& !BLOCK_BLACKLIST_TAG.contains(block)
						&& !(block instanceof FlowingFluidBlock) // Don't speed non-source fluid blocks - dupe issues
						&& !(block instanceof IGrowable)
						&& !(block instanceof IPlantable)) // All plants should be sped using Harvest Goddess
				{
					state.randomTick(world, pos, random);
				}
			}
		}
	}

	private String getTimeName(ItemStack stack)
	{
		byte mode = getTimeBoost(stack);
		switch (mode)
		{
			case 0:
				return "pe.timewatch.off";
			case 1:
				return "pe.timewatch.ff";
			case 2:
				return "pe.timewatch.rw";
			default:
				return "ERROR_INVALID_MODE";
		}
	}

	private byte getTimeBoost(ItemStack stack)
	{
        return stack.getOrCreateTag().getByte("TimeMode");
	}

	private void setTimeBoost(ItemStack stack, byte time)
	{
        stack.getOrCreateTag().putByte("TimeMode", (byte) MathHelper.clamp(time, 0, 2));
	}

	public double getEmcPerTick(int charge)
	{
		int actualCharge = charge + 2;
		return (10.0D * actualCharge) / 20.0D;
	}

	@Override
	public byte getMode(@Nonnull ItemStack stack)
	{
        return stack.getOrCreateTag().getBoolean(TAG_ACTIVE) ? (byte) 1 : 0;
	}

	@Override
	public boolean changeMode(@Nonnull PlayerEntity player, @Nonnull ItemStack stack, Hand hand)
	{
        CompoundNBT tag = stack.getOrCreateTag();
		tag.putBoolean(TAG_ACTIVE, !tag.getBoolean(TAG_ACTIVE));
		return true;
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void addInformation(ItemStack stack, World world, List<ITextComponent> list, ITooltipFlag flags)
	{
		list.add(new TranslationTextComponent("pe.timewatch.tooltip1"));
		list.add(new TranslationTextComponent("pe.timewatch.tooltip2"));

		if (stack.hasTag())
		{
			list.add(new TranslationTextComponent("pe.timewatch.mode").appendSibling(new TranslationTextComponent(getTimeName(stack))));
		}
	}

	@Override
	public void updateInPedestal(@Nonnull World world, @Nonnull BlockPos pos)
	{
		// Change from old EE2 behaviour (universally increased tickrate) for safety and impl reasons.

		if (!world.isRemote && ProjectEConfig.items.enableTimeWatch.get())
		{
			TileEntity te = world.getTileEntity(pos);
			if (te instanceof DMPedestalTile)
			{
				AxisAlignedBB bBox = ((DMPedestalTile) te).getEffectBounds();
				if (ProjectEConfig.effects.timePedBonus.get() > 0) {
					speedUpTileEntities(world, ProjectEConfig.effects.timePedBonus.get(), bBox);
					speedUpRandomTicks(world, ProjectEConfig.effects.timePedBonus.get(), bBox);
				}

				if (ProjectEConfig.effects.timePedMobSlowness.get() < 1.0F) {
					slowMobs(world, bBox, ProjectEConfig.effects.timePedMobSlowness.get());
				}
			}
		}
	}

	@Nonnull
	@Override
	public List<ITextComponent> getPedestalDescription()
	{
		List<ITextComponent> list = new ArrayList<>();
		if (ProjectEConfig.effects.timePedBonus.get() > 0) {
			list.add(new TranslationTextComponent("pe.timewatch.pedestal1", ProjectEConfig.effects.timePedBonus.get()).applyTextStyle(TextFormatting.BLUE));
		}
		if (ProjectEConfig.effects.timePedMobSlowness.get() < 1.0F)
		{
			list.add(new TranslationTextComponent("pe.timewatch.pedestal2", ProjectEConfig.effects.timePedMobSlowness.get()).applyTextStyle(TextFormatting.BLUE));
		}
		return list;
	}

	public static void setInternalBlacklist(Set<TileEntityType<?>> types)
	{
		internalBlacklist = ImmutableSet.copyOf(types);
	}

	@Override
	public int getNumCharges(@Nonnull ItemStack stack)
	{
		return 2;
	}

	@Override
	public boolean showDurabilityBar(ItemStack stack)
	{
		return true;
	}

	@Override
	public double getDurabilityForDisplay(ItemStack stack)
	{
		return 1.0D - (double) getCharge(stack) / getNumCharges(stack);
	}
}
