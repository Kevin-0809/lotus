package com.lotus.gausscmp.config;

import java.util.List;

public record TableFilterConfig(List<String> include, List<String> exclude) {}
