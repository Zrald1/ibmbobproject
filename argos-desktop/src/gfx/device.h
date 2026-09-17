#pragma once

// Shared Direct3D 11 device used by every window (main panel + robot overlay).
// Also hosts the small helpers for compiling HLSL at runtime and building
// vertex/index buffers, so nothing needs to ship as a loose asset file.

#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <string>
#include <unordered_map>
#include <vector>

namespace argos::gfx {

using Microsoft::WRL::ComPtr;

struct Vertex {
    float px, py, pz;
    float nx, ny, nz;
    float u, v;
};

class Device {
public:
    bool create();

    ID3D11Device* d3d() const { return device_.Get(); }
    ID3D11DeviceContext* ctx() const { return ctx_.Get(); }
    IDXGIFactory2* factory() const { return factory_.Get(); }

    // ── Shaders (compiled from embedded source, cached by key) ──
    ID3D11VertexShader* vertex_shader(const std::string& key, const char* source,
                                      const char* entry = "VSMain",
                                      const D3D_SHADER_MACRO* macros = nullptr);
    ID3D11PixelShader* pixel_shader(const std::string& key, const char* source,
                                    const char* entry = "PSMain",
                                    const D3D_SHADER_MACRO* macros = nullptr);

    ID3D11InputLayout* input_layout(const std::string& key, const char* vs_source,
                                    const D3D11_INPUT_ELEMENT_DESC* descs, UINT count);

    // ── Buffers ──
    ComPtr<ID3D11Buffer> create_vertex_buffer(const void* data, size_t bytes);
    ComPtr<ID3D11Buffer> create_index_buffer(const void* data, size_t bytes);
    ComPtr<ID3D11Buffer> create_constant_buffer(size_t bytes);
    // Dynamic buffer for per-frame data (particles, UI quads).
    ComPtr<ID3D11Buffer> create_dynamic_buffer(size_t bytes, UINT bind_flags);

    // ── States ──
    ID3D11BlendState* blend_opaque();
    ID3D11BlendState* blend_alpha();
    ID3D11BlendState* blend_additive();
    ID3D11BlendState* blend_premultiplied();
    ID3D11DepthStencilState* depth_test_write();
    ID3D11DepthStencilState* depth_test_no_write();
    ID3D11DepthStencilState* depth_none();
    ID3D11RasterizerState* raster_solid_cull_back();
    ID3D11RasterizerState* raster_solid_no_cull();
    ID3D11SamplerState* sampler_linear_clamp();
    ID3D11SamplerState* sampler_linear_wrap();

    static std::string compile_error_message(ID3DBlob* errors);

private:
    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> ctx_;
    ComPtr<IDXGIFactory2> factory_;

    std::unordered_map<std::string, ComPtr<ID3D11VertexShader>> vs_cache_;
    std::unordered_map<std::string, ComPtr<ID3D11PixelShader>> ps_cache_;
    std::unordered_map<std::string, ComPtr<ID3D11InputLayout>> layout_cache_;
    std::unordered_map<std::string, ComPtr<ID3D11BlendState>> blend_cache_;
    std::unordered_map<std::string, ComPtr<ID3D11DepthStencilState>> depth_cache_;
    std::unordered_map<std::string, ComPtr<ID3D11RasterizerState>> raster_cache_;
    std::unordered_map<std::string, ComPtr<ID3D11SamplerState>> sampler_cache_;

    ID3D11BlendState* make_blend(const std::string& key, bool enable, D3D11_BLEND src,
                                 D3D11_BLEND dst, D3D11_BLEND src_alpha, D3D11_BLEND dst_alpha,
                                 bool alpha_to_coverage = false);
    ID3D11DepthStencilState* make_depth(const std::string& key, bool test, bool write);
    ID3D11RasterizerState* make_raster(const std::string& key, D3D11_CULL_MODE cull);
    ID3D11SamplerState* make_sampler(const std::string& key, D3D11_TEXTURE_ADDRESS_MODE mode);
};

// Convenience: full-viewport viewport for a texture/swapchain of the given size.
D3D11_VIEWPORT full_viewport(float width, float height);

}  // namespace argos::gfx
