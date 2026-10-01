package works.nuty.codon.client.ui;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Retains vanilla GUI font state, with coverage applied once by the filtered fragment shader. */
public final class CodonTextPipelines {
    public static final RenderPipeline COLOR = create(RenderPipelines.GUI_TEXT, false);
    public static final RenderPipeline GRAYSCALE = create(RenderPipelines.GUI_TEXT_GRAYSCALE, true);

    private CodonTextPipelines() { }

    private static RenderPipeline create(RenderPipeline original, boolean grayscale) {
        var builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("codon", "pipeline/filtered_text" + (grayscale ? "_grayscale" : "")))
            .withVertexShader(original.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("codon", "core/filtered_text"))
            .withShaderDefine("IS_GUI")
            .withDepthStencilState(java.util.Optional.ofNullable(original.getDepthStencilState()))
            .withPolygonMode(original.getPolygonMode())
            .withCull(original.isCull())
            .withPrimitiveTopology(original.getPrimitiveTopology())
            .withPushConstantSize(original.pushConstantSize());
        original.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
        for (int i = 0; i < original.getVertexFormatBindings().size(); i++) builder.withVertexBinding(i, original.getVertexFormatBinding(i));
        for (int i = 0; i < original.getColorTargetStates().size(); i++) builder.withColorTargetState(i, original.getColorTargetStates().get(i));
        if (grayscale) builder.withShaderDefine("IS_GRAYSCALE");
        return builder.build();
    }
}
