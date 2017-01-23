// ===================================================================================================
//                           _  __     _ _
//                          | |/ /__ _| | |_ _  _ _ _ __ _
//                          | ' </ _` | |  _| || | '_/ _` |
//                          |_|\_\__,_|_|\__|\_,_|_| \__,_|
//
// This file is part of the Kaltura Collaborative Media Suite which allows users
// to do with audio, video, and animation what Wiki platfroms allow them to do with
// text.
//
// Copyright (C) 2006-2016  Kaltura Inc.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
// @ignore
// ===================================================================================================
package com.kaltura.client.types;

import org.w3c.dom.Element;
import com.kaltura.client.KalturaParams;
import com.kaltura.client.KalturaApiException;
import com.kaltura.client.utils.ParseUtils;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


/**
 * This class was generated using exec.php
 * against an XML schema provided by Kaltura.
 * 
 * MANUAL CHANGES TO THIS CLASS WILL BE OVERWRITTEN.
 */

@SuppressWarnings("serial")
public class KalturaVerizonVcastDistributionProfile extends KalturaConfigurableDistributionProfile {
    public String ftpHost;
    public String ftpLogin;
    public String ftpPass;
    public String providerName;
    public String providerId;
    public String entitlement;
    public String priority;
    public String allowStreaming;
    public String streamingPriceCode;
    public String allowDownload;
    public String downloadPriceCode;

    public KalturaVerizonVcastDistributionProfile() {
    }

    public KalturaVerizonVcastDistributionProfile(Element node) throws KalturaApiException {
        super(node);
        NodeList childNodes = node.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node aNode = childNodes.item(i);
            String nodeName = aNode.getNodeName();
            String txt = aNode.getTextContent();
            if (nodeName.equals("ftpHost")) {
                this.ftpHost = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("ftpLogin")) {
                this.ftpLogin = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("ftpPass")) {
                this.ftpPass = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("providerName")) {
                this.providerName = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("providerId")) {
                this.providerId = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("entitlement")) {
                this.entitlement = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("priority")) {
                this.priority = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("allowStreaming")) {
                this.allowStreaming = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("streamingPriceCode")) {
                this.streamingPriceCode = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("allowDownload")) {
                this.allowDownload = ParseUtils.parseString(txt);
                continue;
            } else if (nodeName.equals("downloadPriceCode")) {
                this.downloadPriceCode = ParseUtils.parseString(txt);
                continue;
            } 
        }
    }

    public KalturaParams toParams() throws KalturaApiException {
        KalturaParams kparams = super.toParams();
        kparams.add("objectType", "KalturaVerizonVcastDistributionProfile");
        kparams.add("ftpHost", this.ftpHost);
        kparams.add("ftpLogin", this.ftpLogin);
        kparams.add("ftpPass", this.ftpPass);
        kparams.add("providerName", this.providerName);
        kparams.add("providerId", this.providerId);
        kparams.add("entitlement", this.entitlement);
        kparams.add("priority", this.priority);
        kparams.add("allowStreaming", this.allowStreaming);
        kparams.add("streamingPriceCode", this.streamingPriceCode);
        kparams.add("allowDownload", this.allowDownload);
        kparams.add("downloadPriceCode", this.downloadPriceCode);
        return kparams;
    }

}

