/*---------------------------------------------------------------------------------------------
 *  Copyright (c) 2025 Huawei Technologies Co., Ltd. All rights reserved.
 *  This file is a part of the ModelEngine Project.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

package modelengine.fit.example;

import modelengine.fitframework.annotation.Genericable;

/**
 * 表示 Spring 应用需要实现的接口服务。
 *
 * @author 季聿阶
 * @since 2025-02-13
 */
public interface SpringWeather {
    /**
     * 获取天气信息。
     *
     * @return 表示天气信息的 {@link String}。
     */
    @Genericable(id = "SpringWeather")
    String get();
}
