#include "gfx/device.h"

#include <d3dcompiler.h>

#include <cstring>

#include "core/log.h"
#include "platform/win_util.h"

#pragma comment(lib, "d3dcompiler.lib")

namespace argos::gfx {

using Microsoft::WRL::ComPtr;

bool Device::create() {
    const UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT
#ifdef _DEBUG
                       | D3D11_CREATE_DEVICE_DEBUG
#endif
        ;

    static const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1,
        D3D_FEATURE_LEVEL_10_0,
    };

    D3D_FEATURE_LEVEL obtained = D3D_FEATURE_LEVEL_10_0;
    HRESULT hr = ::D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, levels,
                                     static_cast<UINT>(std::size(levels)), D3D11_SDK_VERSION,
                                     &device_, &obtained, &ctx_);

    if (FAILED(hr) && (flags & D3D11_CREATE_DEVICE_DEBUG)) {
        // Debug layer not installed — retry without it rather than dying.
        log::warn("D3D11 debug layer unavailable, creating a plain device");
        hr = ::D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                 flags & ~D3D11_CREATE_DEVICE_DEBUG, levels,
                                 static_cast<UINT>(std::size(levels)), D3D11_SDK_VERSION, &device_,
                                 &obtained, &ctx_);
    }
    if (FAILED(hr)) {
        log::error("D3D11CreateDevice failed: " + win::hr_text(hr));
        return false;
    }

    // The DXGI factory lives on the adapter, not on the device itself.
    Microsoft::WRL::ComPtr<IDXGIDevice> dxgi_device;
    Microsoft::WRL::ComPtr<IDXGIAdapter> adapter;
    if (FAILED(device_.As(&dxgi_device)) || FAILED(dxgi_device->GetAdapter(&adapter)) ||
        FAILED(adapter->GetParent(IID_PPV_ARGS(&factory_)))) {
        log::error("Could not obtain IDXGIFactory2 — Windows 8 or newer is required.");
        return false;
    }

    log::info("Direct3D 11 device ready (feature level " +
              std::to_string(static_cast<int>(obtained) / 0x1000) + "." +
              std::to_string((static_cast<int>(obtained) / 0x100) % 0x10) + ")");
    return true;
}

std::string Device::compile_error_message(ID3DBlob* errors) {
    if (!errors) return "(no compiler output)";
    return std::string(static_cast<const char*>(errors->GetBufferPointer()), errors->GetBufferSize());
}

ID3D11VertexShader* Device::vertex_shader(const std::string& key, const char* source,
                                          const char* entry, const D3D_SHADER_MACRO* macros) {
    if (auto it = vs_cache_.find(key); it != vs_cache_.end()) return it->second.Get();

    ComPtr<ID3DBlob> blob;
    ComPtr<ID3DBlob> errors;
    const HRESULT hr = ::D3DCompile(source, std::strlen(source), key.c_str(), macros, nullptr, entry,
                                    "vs_5_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &blob, &errors);
    if (FAILED(hr)) {
        log::error("Vertex shader '" + key + "' failed to compile: " + compile_error_message(errors.Get()));
        return nullptr;
    }

    ComPtr<ID3D11VertexShader> shader;
    if (FAILED(device_->CreateVertexShader(blob->GetBufferPointer(), blob->GetBufferSize(), nullptr,
                                           &shader))) {
        log::error("CreateVertexShader failed for '" + key + "'");
        return nullptr;
    }
    auto [it, inserted] = vs_cache_.emplace(key, shader);
    return it->second.Get();
}

ID3D11PixelShader* Device::pixel_shader(const std::string& key, const char* source,
                                        const char* entry, const D3D_SHADER_MACRO* macros) {
    if (auto it = ps_cache_.find(key); it != ps_cache_.end()) return it->second.Get();

    ComPtr<ID3DBlob> blob;
    ComPtr<ID3DBlob> errors;
    const HRESULT hr = ::D3DCompile(source, std::strlen(source), key.c_str(), macros, nullptr, entry,
                                    "ps_5_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &blob, &errors);
    if (FAILED(hr)) {
        log::error("Pixel shader '" + key + "' failed to compile: " + compile_error_message(errors.Get()));
        return nullptr;
    }

    ComPtr<ID3D11PixelShader> shader;
    if (FAILED(device_->CreatePixelShader(blob->GetBufferPointer(), blob->GetBufferSize(), nullptr,
                                          &shader))) {
        log::error("CreatePixelShader failed for '" + key + "'");
        return nullptr;
    }
    auto [it, inserted] = ps_cache_.emplace(key, shader);
    return it->second.Get();
}

ID3D11InputLayout* Device::input_layout(const std::string& key, const char* vs_source,
                                        const D3D11_INPUT_ELEMENT_DESC* descs, UINT count) {
    if (auto it = layout_cache_.find(key); it != layout_cache_.end()) return it->second.Get();

    ComPtr<ID3DBlob> blob;
    ComPtr<ID3DBlob> errors;
    if (FAILED(::D3DCompile(vs_source, std::strlen(vs_source), key.c_str(), nullptr, nullptr,
                            "VSMain", "vs_5_0", 0, 0, &blob, &errors))) {
        log::error("Input layout '" + key + "' needs a compilable VS: " + compile_error_message(errors.Get()));
        return nullptr;
    }

    ComPtr<ID3D11InputLayout> layout;
    if (FAILED(device_->CreateInputLayout(descs, count, blob->GetBufferPointer(),
                                          blob->GetBufferSize(), &layout))) {
        log::error("CreateInputLayout failed for '" + key + "'");
        return nullptr;
    }
    auto [it, inserted] = layout_cache_.emplace(key, layout);
    return it->second.Get();
}

ComPtr<ID3D11Buffer> Device::create_vertex_buffer(const void* data, size_t bytes) {
    D3D11_BUFFER_DESC desc{};
    desc.ByteWidth = static_cast<UINT>(bytes);
    desc.Usage = D3D11_USAGE_IMMUTABLE;
    desc.BindFlags = D3D11_BIND_VERTEX_BUFFER;

    D3D11_SUBRESOURCE_DATA init{};
    init.pSysMem = data;

    ComPtr<ID3D11Buffer> buffer;
    device_->CreateBuffer(&desc, &init, &buffer);
    return buffer;
}

ComPtr<ID3D11Buffer> Device::create_index_buffer(const void* data, size_t bytes) {
    D3D11_BUFFER_DESC desc{};
    desc.ByteWidth = static_cast<UINT>(bytes);
    desc.Usage = D3D11_USAGE_IMMUTABLE;
    desc.BindFlags = D3D11_BIND_INDEX_BUFFER;

    D3D11_SUBRESOURCE_DATA init{};
    init.pSysMem = data;

    ComPtr<ID3D11Buffer> buffer;
    device_->CreateBuffer(&desc, &init, &buffer);
    return buffer;
}

ComPtr<ID3D11Buffer> Device::create_constant_buffer(size_t bytes) {
    D3D11_BUFFER_DESC desc{};
    desc.ByteWidth = static_cast<UINT>((bytes + 15) & ~15ull);
    desc.Usage = D3D11_USAGE_DYNAMIC;
    desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    ComPtr<ID3D11Buffer> buffer;
    device_->CreateBuffer(&desc, nullptr, &buffer);
    return buffer;
}

ComPtr<ID3D11Buffer> Device::create_dynamic_buffer(size_t bytes, UINT bind_flags) {
    D3D11_BUFFER_DESC desc{};
    desc.ByteWidth = static_cast<UINT>(bytes);
    desc.Usage = D3D11_USAGE_DYNAMIC;
    desc.BindFlags = bind_flags;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;

    ComPtr<ID3D11Buffer> buffer;
    device_->CreateBuffer(&desc, nullptr, &buffer);
    return buffer;
}

ID3D11BlendState* Device::make_blend(const std::string& key, bool enable, D3D11_BLEND src,
                                     D3D11_BLEND dst, D3D11_BLEND src_alpha, D3D11_BLEND dst_alpha,
                                     bool alpha_to_coverage) {
    if (auto it = blend_cache_.find(key); it != blend_cache_.end()) return it->second.Get();

    D3D11_BLEND_DESC desc{};
    desc.RenderTarget[0].BlendEnable = enable ? TRUE : FALSE;
    desc.RenderTarget[0].SrcBlend = src;
    desc.RenderTarget[0].DestBlend = dst;
    desc.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    desc.RenderTarget[0].SrcBlendAlpha = src_alpha;
    desc.RenderTarget[0].DestBlendAlpha = dst_alpha;
    desc.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    desc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    desc.AlphaToCoverageEnable = alpha_to_coverage ? TRUE : FALSE;

    ComPtr<ID3D11BlendState> state;
    device_->CreateBlendState(&desc, &state);
    auto [it, inserted] = blend_cache_.emplace(key, state);
    return it->second.Get();
}

ID3D11BlendState* Device::blend_opaque() {
    return make_blend("opaque", false, D3D11_BLEND_ONE, D3D11_BLEND_ZERO, D3D11_BLEND_ONE,
                      D3D11_BLEND_ZERO);
}

ID3D11BlendState* Device::blend_alpha() {
    return make_blend("alpha", true, D3D11_BLEND_SRC_ALPHA, D3D11_BLEND_INV_SRC_ALPHA,
                      D3D11_BLEND_ONE, D3D11_BLEND_INV_SRC_ALPHA);
}

ID3D11BlendState* Device::blend_additive() {
    // Premultiplied add: colour and alpha both accumulate, which is what the
    // neon glow needs to build up over a transparent desktop.
    return make_blend("additive", true, D3D11_BLEND_ONE, D3D11_BLEND_ONE, D3D11_BLEND_ONE,
                      D3D11_BLEND_ONE);
}

ID3D11BlendState* Device::blend_premultiplied() {
    return make_blend("premultiplied", true, D3D11_BLEND_ONE, D3D11_BLEND_INV_SRC_ALPHA,
                      D3D11_BLEND_ONE, D3D11_BLEND_INV_SRC_ALPHA);
}

ID3D11DepthStencilState* Device::make_depth(const std::string& key, bool test, bool write) {
    if (auto it = depth_cache_.find(key); it != depth_cache_.end()) return it->second.Get();

    D3D11_DEPTH_STENCIL_DESC desc{};
    desc.DepthEnable = test ? TRUE : FALSE;
    desc.DepthWriteMask = write ? D3D11_DEPTH_WRITE_MASK_ALL : D3D11_DEPTH_WRITE_MASK_ZERO;
    desc.DepthFunc = D3D11_COMPARISON_LESS_EQUAL;

    ComPtr<ID3D11DepthStencilState> state;
    device_->CreateDepthStencilState(&desc, &state);
    auto [it, inserted] = depth_cache_.emplace(key, state);
    return it->second.Get();
}

ID3D11DepthStencilState* Device::depth_test_write() { return make_depth("test_write", true, true); }
ID3D11DepthStencilState* Device::depth_test_no_write() { return make_depth("test_no_write", true, false); }
ID3D11DepthStencilState* Device::depth_none() { return make_depth("none", false, false); }

ID3D11RasterizerState* Device::make_raster(const std::string& key, D3D11_CULL_MODE cull) {
    if (auto it = raster_cache_.find(key); it != raster_cache_.end()) return it->second.Get();

    D3D11_RASTERIZER_DESC desc{};
    desc.FillMode = D3D11_FILL_SOLID;
    desc.CullMode = cull;
    desc.FrontCounterClockwise = FALSE;
    desc.DepthClipEnable = TRUE;

    ComPtr<ID3D11RasterizerState> state;
    device_->CreateRasterizerState(&desc, &state);
    auto [it, inserted] = raster_cache_.emplace(key, state);
    return it->second.Get();
}

ID3D11RasterizerState* Device::raster_solid_cull_back() {
    return make_raster("solid_cull_back", D3D11_CULL_BACK);
}
ID3D11RasterizerState* Device::raster_solid_no_cull() {
    return make_raster("solid_no_cull", D3D11_CULL_NONE);
}

ID3D11SamplerState* Device::make_sampler(const std::string& key, D3D11_TEXTURE_ADDRESS_MODE mode) {
    if (auto it = sampler_cache_.find(key); it != sampler_cache_.end()) return it->second.Get();

    D3D11_SAMPLER_DESC desc{};
    desc.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    desc.AddressU = mode;
    desc.AddressV = mode;
    desc.AddressW = mode;
    desc.MaxLOD = D3D11_FLOAT32_MAX;

    ComPtr<ID3D11SamplerState> state;
    device_->CreateSamplerState(&desc, &state);
    auto [it, inserted] = sampler_cache_.emplace(key, state);
    return it->second.Get();
}

ID3D11SamplerState* Device::sampler_linear_clamp() {
    return make_sampler("linear_clamp", D3D11_TEXTURE_ADDRESS_CLAMP);
}
ID3D11SamplerState* Device::sampler_linear_wrap() {
    return make_sampler("linear_wrap", D3D11_TEXTURE_ADDRESS_WRAP);
}

D3D11_VIEWPORT full_viewport(float width, float height) {
    D3D11_VIEWPORT vp{};
    vp.TopLeftX = 0;
    vp.TopLeftY = 0;
    vp.Width = width;
    vp.Height = height;
    vp.MinDepth = 0.0f;
    vp.MaxDepth = 1.0f;
    return vp;
}

}  // namespace argos::gfx
