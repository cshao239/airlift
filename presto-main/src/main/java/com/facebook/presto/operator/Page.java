/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.operator;

import com.facebook.presto.block.Block;
import com.google.common.base.Preconditions;
import io.airlift.units.DataSize;
import io.airlift.units.DataSize.Unit;

import java.util.Arrays;

public class Page
{
    private final Block[] blocks;

    public Page(Block... blocks)
    {
        Preconditions.checkNotNull(blocks, "blocks is null");
        Preconditions.checkArgument(blocks.length > 0, "blocks is empty");
        this.blocks = Arrays.copyOf(blocks, blocks.length);
    }

    public int getChannelCount()
    {
        return blocks.length;
    }

    public int getPositionCount()
    {
        return blocks[0].getPositionCount();
    }

    public DataSize getDataSize()
    {
        long dataSize = 0;
        for (Block block : blocks) {
            dataSize += block.getDataSize().toBytes();
        }
        return new DataSize(dataSize, Unit.BYTE);
    }

    public Block[] getBlocks()
    {
        return blocks.clone();
    }

    public Block getBlock(int channel)
    {
        return blocks[channel];
    }
}