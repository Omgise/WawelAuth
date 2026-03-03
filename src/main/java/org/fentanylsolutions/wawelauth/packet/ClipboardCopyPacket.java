package org.fentanylsolutions.wawelauth.packet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class ClipboardCopyPacket implements IMessage {

    private String text;
    private String description;

    public ClipboardCopyPacket() {}

    public ClipboardCopyPacket(String text, String description) {
        this.text = text == null ? "" : text;
        this.description = description == null ? "" : description;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.text = ByteBufUtils.readUTF8String(buf);
        this.description = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.text == null ? "" : this.text);
        ByteBufUtils.writeUTF8String(buf, this.description == null ? "" : this.description);
    }

    public static final class Handler implements IMessageHandler<ClipboardCopyPacket, IMessage> {

        @Override
        public IMessage onMessage(ClipboardCopyPacket message, MessageContext ctx) {
            if (!ctx.side.isClient()) {
                return null;
            }

            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.func_152344_a(() -> { // Minecraft.addScheduledTask
                if (message.text != null && !message.text.isEmpty()) {
                    GuiScreen.setClipboardString(message.text);
                }
                if (message.description != null && !message.description.isEmpty() && minecraft.thePlayer != null) {
                    minecraft.thePlayer.addChatMessage(new ChatComponentText(message.description));
                }
            });
            return null;
        }
    }
}
