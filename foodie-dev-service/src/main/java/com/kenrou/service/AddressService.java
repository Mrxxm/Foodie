package com.kenrou.service;

import com.kenrou.pojo.UserAddress;
import com.kenrou.pojo.bo.AddressBO;

import java.util.List;

public interface AddressService {

    /**
     * 查询用户收货地址列表
     */
    public List<UserAddress> queryAll(String userId);

    /**
     * 添加地址
     */
    public void addNewUserAddress(AddressBO addressBO);

    /**
     * 更新地址
     */
    public void updateUserAddress(AddressBO addressBO);

    /**
     * 删除地址
     */
    public void deleteUserAddress(String userId, String addressId);

    /**
     * 设为默认地址
     */
    public void setDefaultAddress(String userId, String addressId);

    /**
     * 根据用户id和地址id查询具体用户地址对象信息
     */
    public UserAddress queryUserAddress(String userId, String addressId);
}
