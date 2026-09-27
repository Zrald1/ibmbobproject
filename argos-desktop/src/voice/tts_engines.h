#pragma once

#include <string>
#include <string_view>

namespace argos::voice {

std::wstring speech_clean(const std::string& md);
std::wstring xml_escape(std::wstring_view s);

bool voicebox_speak(const std::string& text8);
void murf_speak(const std::string& text8);
void speechmatics_speak(const std::string& text8);
void sapi_speak(const std::wstring& clean);

}  // namespace argos::voice
