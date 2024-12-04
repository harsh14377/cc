package com.tekinsure.tapas.runtime.premium.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tekinsure.apps.common.functional.Function1Void;
import com.tekinsure.refdata.pal.ReferenceDataCriteria;
import com.tekinsure.tapas.common.api.service.CommonServices;
import com.tekinsure.ui.sureui.model.UIElement;

import com.tekinsure.apps.common.model.Amount;
import com.tekinsure.collaborus.admindata.model.Scheme;
import com.tekinsure.collaborus.admindata.model.SchemeConfig;
import com.tekinsure.dom.gi.NamedInfo;
import com.tekinsure.dom.gi.Policy;
import com.tekinsure.dom.gi.PolicyTransaction;
import com.tekinsure.pdk.common.rating.PremiumAdjustmentType;
import com.tekinsure.pdk.common.riskdata.Charge;
import com.tekinsure.pdk.common.riskdata.GOMStatus;
import com.tekinsure.pdk.common.riskdata.ROMCoverage;
import com.tekinsure.pdk.common.riskdata.ROMFee;
import com.tekinsure.pdk.common.riskdata.ROMLocation;
import com.tekinsure.pdk.common.riskdata.ROMPayables;
import com.tekinsure.pdk.common.riskdata.ROMPayablesObject;
import com.tekinsure.pdk.common.riskdata.ROMPremium;
import com.tekinsure.pdk.common.riskdata.ROMPremiumOption;
import com.tekinsure.pdk.common.riskdata.ROMPremiumType;
import com.tekinsure.pdk.common.riskdata.ROMRatedPayables;
import com.tekinsure.pdk.common.riskdata.ROMSubCoverage;
import com.tekinsure.rules.base.DateUtils;
import com.tekinsure.session.utils.SessionUtils;
import com.tekinsure.tapas.common.api.constants.DECategory;
import com.tekinsure.tapas.common.api.constants.PremiumValueKey;
import com.tekinsure.tapas.common.api.constants.ProductConstants;
import com.tekinsure.tapas.common.api.constants.ProductOptionsCode;
import com.tekinsure.tapas.common.api.constants.UIConstants;
import com.tekinsure.tapas.common.api.event.Event;
import com.tekinsure.tapas.common.api.event.EventAction;
import com.tekinsure.tapas.common.api.event.FeeEvent;
import com.tekinsure.tapas.common.api.event.PremiumEvent;
import com.tekinsure.tapas.common.api.model.DECharge;
import com.tekinsure.tapas.common.api.model.DEConfig;
import com.tekinsure.tapas.common.api.model.DataElement;
import com.tekinsure.tapas.common.api.model.Product;
import com.tekinsure.tapas.common.api.model.ProductOption;
import com.tekinsure.tapas.common.api.service.CommonRuntimeService;
import com.tekinsure.tapas.common.api.service.EventService;
import com.tekinsure.tapas.common.premium.PremiumConstants;
import com.tekinsure.tapas.common.premium.PremiumUtils;
import com.tekinsure.tapas.common.premium.service.LeapYearStrategy;
import com.tekinsure.tapas.common.premium.service.LeapYearStrategyFactory;
import com.tekinsure.tapas.common.premium.util.PremiumCalcUtils;
import com.tekinsure.tapas.common.ui.LabelModelUtils;
import com.tekinsure.tapas.common.ui.TAPASAppContext;
import com.tekinsure.tapas.common.utils.MarketingPlanUtils;
import com.tekinsure.tapas.common.utils.ProductUtils;
import com.tekinsure.tapas.plugin.PluginManagerInstance;
import com.tekinsure.tapas.plugin.ServiceManager;
import com.tekinsure.tapas.runtime.ui.AppServices;
import com.tekinsure.tapas.runtime.api.model.constants.CountryCode;
import com.tekinsure.tapas.runtime.api.model.risk.TAPASRiskData;
import com.tekinsure.ui.common.utils.PolicyTransactionHelper;
import com.tekinsure.ui.pureui.utils.IconFlag;
import com.tekinsure.ui.pureui.utils.PureUIConstants;
import com.tekinsure.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that handles reading and writing to the policy. Reads all payables in as PremiumRows, and handles
 * writing those objects back into the riskData.
 */
public class PremiumRowProcessor {
    protected static final Logger LOG = LoggerFactory.getLogger(PremiumRowProcessor.class);
    private final boolean showStateFlags;
    private final String newLabel;
    private final String deletedLabel;
    private List<PremiumRow> rows = new ArrayList<>();
    private List<PremiumRow> fees = new ArrayList<>();
    private List<PremiumColumn> columns;
    private Map<String, String> editabilityDefaults;
    private Map<String, PremiumRow> rowDictionary = new HashMap<>();
    private boolean anyRowEditable = false;
    private Product prod;
    private Policy pom;
    private boolean hideTotalDummyRow;
    private static final String CUSTOMVARIANT = "CUSTOMVARIANT";
    private static final String CUSTOMVARIANT_NAME = "CUSTOMVARIANT_NAME";

    public PremiumRowProcessor(Product prod, Policy pom, ROMPayablesObject root, List<PremiumColumn> columns, Map<String, String> editabilityDefaults) {
        this.prod = prod;
        this.pom = pom;
        this.columns = columns;
        this.editabilityDefaults = editabilityDefaults;

        // Labels for structure status flags
        // Show on all transactions after quote
        showStateFlags = !PolicyTransactionHelper.isQuoteNewBusinessRenewal(pom) && !PolicyTransactionHelper.isRenewalInvitation(pom);
        newLabel = LabelModelUtils.getDELabel(prod, prod.getDataElementByBusinessPath(ProductConstants.LABEL_STATUS_NEW_BUSINESS_PATH), "New");
        deletedLabel = LabelModelUtils.getDELabel(prod, prod.getDataElementByBusinessPath(ProductConstants.LABEL_STATUS_DELETED_BUSINESS_PATH), "Deleted");

        // Backend call to verify adjustability of rows
        Set<ROMPayablesObject> adjustables = new HashSet<>();
        EventService eventService = PluginManagerInstance.get().getService(prod, EventService.class);

        // Payables adjustable's
        Event event = new PremiumEvent(PremiumEvent.EventName.GetAdjustablePayableObjects, pom, prod);
        eventService.processEvent(event, EventAction.NullEventAction);
        Object property = event.getProperty(PremiumEvent.EventArg.AdjustablePayableObjects);
        if (property != null && property instanceof List) {
            adjustables.addAll((List<ROMPayablesObject>) property);
        }

        // Fee adjustable's
        event = new FeeEvent(FeeEvent.EventName.GetAdjustablePayableObjects, pom, prod);
        eventService.processEvent(event, EventAction.NullEventAction);
        property = event.getProperty(FeeEvent.EventArg.AdjustablePayableObjects);
        if (property != null && property instanceof List) {
            adjustables.addAll((List<ROMPayablesObject>) property);
        }

        // Populate tree
        process(root, adjustables);

        List<PremiumRow> locationRows = new ArrayList<>();
        List<PremiumRow> policyRows = new ArrayList<>();
        for (PremiumRow row : rows) {
            if (row.getHeading() != null && !(row.isStampDutyRow() || row.isPolicyCostRow() || row.isIaLevyRow() || row.isEciLevyRow()) &&
                    !(row.getPayables().size() > 0 && "DE000001".equals(row.getPayables().getDataElementCode()))) {
                locationRows.add(row);
            } else {
                policyRows.add(row);
            }
        }
        rows.clear();

        try{
            for(PremiumRow row : locationRows){
                for(PremiumRow child : row.getChildren()){
                    UIElement childUE = getUIElementByDataElement(prod.getDataDictionary().get(child.getKey().substring(child.getKey().length()-8))).get(0);
                    if( childUE.getElementProperties().get(PureUIConstants.IS_MATERIAL_DAMAGE)!= null &&  childUE.getElementProperties().get(PureUIConstants.IS_MATERIAL_DAMAGE).equalsIgnoreCase("true")){
                        PremiumRow packageCov = null;
                        for(PremiumRow covRow : child.getChildren()){
                            UIElement covUE = getUIElementByDataElement(prod.getDataDictionary().get(covRow.getKey().substring(covRow.getKey().length()-8))).get(0);
                            if(covUE.getElementProperties().get(PureUIConstants.IS_PACKAGE_EARTH_QUAKE)!=null && covUE.getElementProperties().get(PureUIConstants.IS_PACKAGE_EARTH_QUAKE).equalsIgnoreCase("true")){
                                packageCov = covRow;
                            }
                        }
                        if(packageCov!=null) {
                            child.getChildren().remove(packageCov);
                            child.getChildren().add(packageCov);
                        }
                    }
                }
            }
        }catch(Exception e){}

        rows.addAll(locationRows);
        rows.addAll(policyRows);

        // Cull dead branches
        walkTree(RecursionType.Children, (Function1Void<PremiumRow>) row -> {
            if (row.getState() == PremiumRow.State.Hidden) {            // Cull hidden rows
                if (row.getParent() != null) {
                    row.getParent().getChildren().remove(row);
                } else {                        // No parent means it's a location or policy row.
                    rows.remove(row);
                }
            }
        });
        DataElement tableDE = ProductUtils.getSessionProduct().getDataDictionary().get(PremiumConstants.PREMIUM_DETAILS);
        DEConfig hideRow = tableDE.findConfig(PremiumConstants.MODULE_NAME, PremiumConstants.HIDE_DUMMY_TOTAL_ROW);
        hideTotalDummyRow = hideRow == null || Boolean.parseBoolean(hideRow.getValue());

        // Add Total Row's for top level items.
        for (PremiumRow row : rows) {
            if (row.getParent() == null && !row.isTotalRow()) {
                row.getChildren().add(createTotalsRow(row));
            }
        }

        // Index remaining rows
        int i = 1;
        for (PremiumRow row : listFlattenedRows()) {
            row.setRowNumber(i++);
        }
    }

    private List<UIElement> getUIElementByDataElement(DataElement dataElement) {
        CommonServices commonServices = PluginManagerInstance.get().getService(dataElement.getProduct(), CommonServices.class);
        ReferenceDataCriteria criteria = commonServices.getCommonModuleService().createSearchCriteria(dataElement.getProduct(), UIElement.class);
        criteria.eq("dataFieldId", dataElement.getDataElementCode());
        return criteria.listObjects(UIElement.class);
    }


    /**
     * Processes the given object, extracting all payables as PremiumRows. Recurses through the structure, populating any
     * child rows. Any instances of ROMLocation found are added to the rows field, and not returned
     *
     * @param object      the object to be processed. Could be a ROMObject, List, or value
     * @param adjustables A collection of objects indicating what can have it's values modified, and what has been fixed
     *                    and should not be changed.
     * @return A list of PremiumRows representing all ROMPayableObjects found in the hierarchy.
     */
    private List<PremiumRow> process(Object object, Collection<ROMPayablesObject> adjustables) {
        ArrayList<PremiumRow> returnList = new ArrayList<>();

        // Object is a payables, we create a new PremiumRow, populate it, and process the payables children.
        if (object instanceof ROMPayablesObject) {
            ROMPayablesObject payable = (ROMPayablesObject) object;
            boolean adjustable = adjustables.contains(payable);

            PremiumRow row = createRow(payable, adjustable);

            rowDictionary.put(row.getKey(), row);

            if (object instanceof ROMFee) {
                // ROMFees get added to the Fees list, and don't require the extra processing of other types
                if (payable != null && payable.getFeePayables() != null) {
                    row.setFee(true);
                    row.setOriginalROMPayables(payable.getFeePayables().deepCopy());
                    fees.add(row);
                }
            } else {

                // ROMLocations get added to the rows field, as they are top level objects, everything else gets returned as a child
                if (object instanceof ROMLocation) {
                    rows.add(row);
                } else {
                    returnList.add(row);
                }

                createPolicyLevelStampDutyRow(row);

                // Process all the children of this element
                for (Object value : payable.values()) {
                    row.getChildren().addAll(process(value, adjustables));
                }

                // Populate source type for breakdowns
                if (!row.getChildren().isEmpty()) {
                    String type = null;
                    for (PremiumRow child : row.getChildren()) {
                        if (type == null) {
                            // Pick up first child type
                            type = child.getSourceType();
                        } else if (!type.equals(child.getSourceType())) {
                            // If there's two different types, parent is mixed
                            type = PremiumRow.SOURCE_MIXED;
                            break;
                        }
                    }
                    row.setSourceType(type);
                }

                // Only branches ending in a premium type, coverage or subcoverage should be considered
                if (row.getChildren().isEmpty() && !(object instanceof ROMCoverage || object instanceof ROMSubCoverage
                        || object instanceof ROMPremiumType)) {

                    // We have to add locations before the recursion to maintain proper ordering...
                    // So we have to remove them afterwards if they're not valid any more.
                    if (object instanceof ROMLocation) {
                        rows.remove(row);
                    }
                    return new ArrayList<>();
                }

                // Populate parents in the heirachy
                for (PremiumRow child : row.getChildren()) {
                    child.setParent(row);
                }
            }

            // sort the premium types at every level
            row.getChildren().sort((o1, o2) -> {
                DataElement de1 = prod.getDataDictionary().get(o1.getPayables().getDataElementCode());
                DataElement de2 = prod.getDataDictionary().get(o2.getPayables().getDataElementCode());
                if (de1.getType().getCategory().equals(DECategory.PremiumType) && de2.getType().getCategory().equals(DECategory.PremiumType)) {
                    return o1.getHeading().compareTo(o2.getHeading());
                }
                return 0;
            });

        } else if (object instanceof List) {
            // Search through lists (Locations are stored as a ROMList)
            for (Object o : ((List) object)) {
                returnList.addAll(process(o, adjustables));
            }
        }
        return returnList;
    }

    /**
     * Create a new PremiumRow object and populate it.
     */
    private PremiumRow createRow(ROMPayablesObject payable, boolean adjustable) {
        String dataElementCode = payable.getDataElementCode();
        DataElement dataElement = prod.getDataDictionary().get(dataElementCode);

        PremiumRow row = new PremiumRow(payable);

        // Set editability state
        if (payable.getStatus().getCurrent().equals(GOMStatus.DELETED)) {
            row.setDeleted(true);
        }
        // Read in DE Config for editability
        DEConfig config = dataElement.findConfig(PremiumConstants.MODULE_NAME, editabilityDefaults.get("config"));
        String editability;
        if (config == null) {        // if no de specific config is found use default for that type.
            editability = editabilityDefaults.get(dataElement.getTypeCode());
        } else {                            // Otherwise use the DE specific config
            editability = config.getValue();
        }

        // If no config existed, map will be empty. Use editable as default
        if (StringUtils.isEmpty(editability)) {
            editability = PremiumConstants.EDITABLE;
        }

        if ((!adjustable || ServiceManager.getRuntimeService().isConsumer())
                && !editability.equals(PremiumConstants.HIDDEN)) {
            // Anything not in the list of adjustablePayable objects is read only.
            // Consumer and Producer will be always readonly or Hidden
            editability = PremiumConstants.READONLY;
        }

        switch (editability) {
            case PremiumConstants.READONLY:
                row.setState(PremiumRow.State.ReadOnly);
                break;
            case PremiumConstants.HIDDEN:
                row.setState(PremiumRow.State.Hidden);
                break;
            default:
                row.setState(PremiumRow.State.Editable);
                anyRowEditable = true;
        }

        if (payable instanceof ROMPremiumType) {
            row.setSourceType(((ROMPremiumType) payable).getTypeCode());
        }

        BigDecimal terrorismPremium = payable.getPayables().getVariant().getChargedPremium().getTerrorismPremium();
        Boolean isTerrorismPremiumRow = (Boolean) payable.get(PremiumConstants.IS_TERRORISM_PREMIUM);
        if (terrorismPremium != null && ((isTerrorismPremiumRow != null && isTerrorismPremiumRow) || terrorismPremium.compareTo(BigDecimal.ZERO) != 0)) {
            row.setTerrorismPremiumRow(true);
        }

        row.setKey(payable.getPath());
        row.setCalcType(dataElement.getPremiumCalcType());

        if (dataElementCode.equals("DE000001")) {
            ProductOption quote_options_overlay = ServiceManager.getCommonServices().getCommonProductOptionService().getProductOption(ProductUtils.getSessionProduct(), ProductOptionsCode.PREMIUM_OPTIONS_OVERLAY);
            if (quote_options_overlay != null && Boolean.parseBoolean(quote_options_overlay.getDefaultValue())) {
                Policy policy = SessionUtils.getCurrentPOM();
                Product product = ProductUtils.getSessionProduct();
                boolean customVariant = Boolean.parseBoolean(MarketingPlanUtils.getSchemeConfigFieldValue(CUSTOMVARIANT));
                CommonRuntimeService runtimeService = ServiceManager.getRuntimeService(product);
                List<ROMPremiumOption> premiumOptions = runtimeService.getAllPremiumOptions(policy);
                String premiumOptnName = null;
                if (customVariant) {
                    row.setHeading(MarketingPlanUtils.getSchemeConfigFieldValue(CUSTOMVARIANT_NAME));
                    TAPASRiskData.getRiskData(policy).getRoot().put(UIConstants.PREMIUM_OPTION_NAME, MarketingPlanUtils.getSchemeConfigFieldValue(CUSTOMVARIANT_NAME));
                } else if (premiumOptions != null && premiumOptions.size() > 1) {
                    for (ROMPremiumOption premiumOption : premiumOptions) {
                        if (premiumOption.isSelectedOption()) {
                            premiumOptnName = MarketingPlanUtils.getSchemeConfigFieldValue(product.getDataDictionary().get(premiumOption.getDataElementCode()).getBusinessCode());
                            row.setHeading(premiumOptnName);
                            TAPASRiskData.getRiskData(policy).getRoot().put(UIConstants.PREMIUM_OPTION_NAME, premiumOptnName);
                            break;
                        } else if (PolicyTransaction.Quote.equals(policy.getPolicyTransaction()) || PolicyTransaction.Policy.equals(policy.getPolicyTransaction())) {
                            row.setHeading(UIConstants.NO_PLAN_SELECTED);
                        }
                    }
                }
                if (premiumOptnName == null) {
                    Object premiumOptnNameObj = TAPASRiskData.getRiskData(policy).getRoot().get(UIConstants.PREMIUM_OPTION_NAME);
                    if (premiumOptnNameObj != null)
                        row.setHeading(premiumOptnNameObj.toString());
                }
            } else {
                row.setHeading((PremiumUtils.getStructureHeading(dataElement, payable)));
                if (dataElementCode.equals("DE000001") && !hideTotalDummyRow) {
                    row.setVisible(false);
                }
            }
        } else {
            row.setHeading(PremiumUtils.getStructureHeading(dataElement, payable));
            if (dataElementCode.equals("DE000001") && !hideTotalDummyRow) {
                row.setVisible(false);
            }
        }
        row.setColumns(columns);

        // On non-quote transactions, flag changed states
        if (showStateFlags) {
            if (payable.isNew()) {
                IconFlag flag = new IconFlag(PureUIConstants.STATUS_NEW_CLASS, newLabel);
                row.getFlags().add(flag);
            } else if (payable.isDeleted()) {
                IconFlag flag = new IconFlag(PureUIConstants.STATUS_DELETED_CLASS, deletedLabel);
                row.getFlags().add(flag);
            }
        }

        refresh(row);

        return row;
    }

    /**
     * Resets all the values in the tree.
     */
    public void resetAll() {
        List<PremiumRow> topLevel = listRowsAndFees();
        resetTree(RecursionType.Children, topLevel.toArray(new PremiumRow[0]));
    }

    /**
     * Resets the values in a given row and all of its children and parents.
     */
    public void resetSubTree(PremiumRow row) {
        resetTree(RecursionType.Both, row);
    }

    /**
     * Utility method, calls reset() on all elements touched by walkTree()
     */
    private void resetTree(RecursionType recurse, PremiumRow... start) {
        walkTree(recurse, new Function1Void<PremiumRow>() {
            @Override
            public void apply(PremiumRow arg1) {
                reset(arg1);
            }
        }, start);
    }

    /**
     * Resets the values in a premium row, undoing all changes and setting all user values to their original value
     */
    public void reset(PremiumRow row) {
        row.getUserValues().clear();
        row.getUserValues().putAll(row.getOriginalValues());
        resetPayables(row);
        row.setShowUndo(false);
    }

    /**
     * Resets all the values in the tree, undoing all changes and setting all user values to their original value
     */
    public void refreshAll() {
        List<PremiumRow> topLevel = listRowsAndFees();
        refreshTree(RecursionType.Children, topLevel.toArray(new PremiumRow[topLevel.size()]));
    }

    /**
     * Resets the values in a given row and all of its children and parents.
     */
    public void refreshSubTree(PremiumRow row) {
        refreshTree(RecursionType.Both, row);
    }

    /**
     * Utility method, calls reset() on all elements touched by walkTree()
     */
    private void refreshTree(RecursionType recurse, PremiumRow... start) {
        walkTree(recurse, new Function1Void<PremiumRow>() {
            @Override
            public void apply(PremiumRow arg1) {
                refresh(arg1);
            }
        }, start);
    }

    /**
     * Resets the values in a premium row, undoing all changes and setting all user values to their original value
     */
    public void refresh(PremiumRow row) {
        ROMPayablesObject payable = row.getPayables();
        ROMRatedPayables ratedPayables = null;
        if (payable.getPayables() instanceof ROMRatedPayables) {
            ratedPayables = (ROMRatedPayables) payable.getPayables();
        }
        ROMPremium technicalPremium;
        ROMPremium chargedPremium;
        String technicalCommissionRate = null;
        String chargedCommissionRate = null;

        if (row.getPayables() instanceof ROMFee) {  // Fee's save to feePayables, everything else uses regular payables
            if (payable.getFeePayables() == null) {
                return;
            }
            technicalPremium = payable.getFeePayables().getVariant().getTechnicalPremium();
            chargedPremium = payable.getFeePayables().getVariant().getChargedPremium();
        } else {
            technicalPremium = payable.getPayables().getVariant().getTechnicalPremium();
            //Technical Commission Rate is obtained from the rated payables for a rated node. For other nodes it is calculated as a ratio of commission amount over gross premium
            technicalCommissionRate = getCommissionRate(technicalPremium, ratedPayables, true);

            chargedPremium = payable.getPayables().getVariant().getChargedPremium();
            //Charged Commission Rate is obtained from the rated payables for a rated node. For other nodes it is calculated as a ratio of commission amount over gross premium
            chargedCommissionRate = getCommissionRate(chargedPremium, ratedPayables, false);
        }

        row.getUserValues().clear();
        row.getOriginalValues().clear();

        // Initialise values in the map
        for (PremiumColumn column : columns) {
            String value = null;

            try {
                switch (column.getColumnCode()) {
                    case NetTechnicalPremium:
                        value = technicalPremium.getNetPremium().toString();
                        break;
                    case NetChargedPremium:
                        value = chargedPremium.getNetPremium().toString();
                        break;
                    case TechnicalCommission:
                        value = technicalPremium.getCommission().toString();
                        row.getUserValues().put(column.getColumnCode().toString() + "Percent", technicalCommissionRate);
                        break;
                    case ChargedCommission:
                        value = chargedPremium.getCommission().toString();
                        row.getUserValues().put(column.getColumnCode().toString() + "Percent", chargedCommissionRate);
                        break;
                    case CommissionPercentage:
                        row.getUserValues().put(column.getColumnCode().toString() + "Technical", technicalCommissionRate);
                        row.getUserValues().put(column.getColumnCode().toString() + "Charged", chargedCommissionRate);
                        row.getOriginalValues().put(column.getColumnCode().toString() + "Charged", chargedCommissionRate);
                        break;
                    case GrossChargedPremium:
                        value = chargedPremium.getPremium().toString();
                        break;
                    case GrossTechnicalPremium:
                        value = technicalPremium.getPremium().toString();
                        break;
                    case ChargedPremiumAdjusted:
                        value = PremiumUtils.getAdjustmentPercent(technicalPremium.getPremium(), chargedPremium.getPremium()).toString();
                        break;
                    case ChargedCommissionAdjusted:
                        value = PremiumUtils.getAdjustmentPercent(technicalPremium.getCommission(), chargedPremium.getCommission()).toString();
                        break;
                    case TaxesAndCharges:
                        if (chargedPremium.getTotalCharges() != null) {
                            value = chargedPremium.getTotalCharges().toString();
                        }
                        break;
                    case TaxesAndChargesAdjusted:
                        if (technicalPremium.getTotalCharges() != null) {
                            value = PremiumUtils.getAdjustmentPercent(technicalPremium.getTotalCharges(), chargedPremium.getTotalCharges()).toString();
                        }
                        break;
                    case TotalPremium:
                        value = chargedPremium.getTotalPremium().toString();
                        break;
                    case TotalPremiumAdjusted:
                        value = PremiumUtils.getAdjustmentPercent(technicalPremium.getTotalPremium(), chargedPremium.getTotalPremium()).toString();
                        break;
                    case Rate:
                        row.getUserValues().put(column.getColumnCode().toString() + "Technical", technicalPremium.getPremiumRate().toString());
                        row.getUserValues().put(column.getColumnCode().toString() + "Charged", chargedPremium.getPremiumRate().toString());
                        row.getOriginalValues().put(column.getColumnCode().toString() + "Charged", chargedPremium.getPremiumRate().toString());
                        value = null;
                        break;
                    case AnnualisedPremium:
                        Policy policy = SessionUtils.getCurrentPOM();
                        BigDecimal fapBase = policy.getAdjustedPayable() == null ? BigDecimal.ZERO : policy.getAdjustedPayable().getFAPremium();
                        BigDecimal fapTaxes = policy.getAdjustedPayable() == null ? BigDecimal.ZERO : policy.getAdjustedPayable().getFATax();
                        value = fapBase.add(fapTaxes).toString();
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                // TODO EP skip failed reads for now
            }

            if (value != null) {
                row.getUserValues().put(column.getColumnCode().toString(), value);
                row.getOriginalValues().put(column.getColumnCode().toString(), value);
            }
        }

        DEConfig deConfig = ProductUtils.getSessionProduct().getDataDictionary().get(PremiumConstants.PREMIUM_DETAILS).findConfig(PremiumConstants.MODULE_NAME, PremiumConstants.HIDE_MIN_PREMIUM_FLAG);
        boolean hideIcon = false;
        if (deConfig != null && deConfig.getValue() != null) {
            hideIcon = Boolean.parseBoolean(deConfig.getValue()) && ServiceManager.getRuntimeService().isProducer();
        }

        // remove all Min Premium flags and apply again if payable hit min premium
        row.getFlags().removeIf(flag -> PremiumConstants.PremiumColumnCode.MinimumPremium.toString().equals(flag.getTitle()));
        if (payable.getPayables().getMinPremiumApplied() && !hideIcon) {
            IconFlag flag = new IconFlag(PureUIConstants.MIN_PREMIUM_FLAG_CLASS, PremiumConstants.PremiumColumnCode.MinimumPremium.toString(), "Minimum Premium");
            row.getFlags().add(flag);
        }

        Map<String, ROMPayables> typePayables = ServiceManager.getRuntimeService().getPremiumTypePayables(row.getPayables(), pom);
        row.getFlags().removeIf(flag -> "premiumTypeMinPremium".equals(flag.getTitle()));

        for (Map.Entry<String, ROMPayables> entry : typePayables.entrySet()) {
            if (entry.getValue().getMinPremiumApplied() && !hideIcon) {
                IconFlag minFlag = new IconFlag(PureUIConstants.MIN_PREMIUM_FLAG_CLASS + " premium-types", "premiumTypeMinPremium", "Minimum Premium at child");
                row.getFlags().add(minFlag);
                break;
            }
        }
    }

    /**
     * Expands or collapses all parents and children of the given set of rows.
     */
    public void setTreeExpanded(final boolean open, PremiumRow... rows) {
        walkTree(RecursionType.Children, new Function1Void<PremiumRow>() {
            @Override
            public void apply(PremiumRow arg1) {
                arg1.setExpanded(open);
            }
        }, rows);
    }

    /**
     * Locks charges or inputs on all parents and children of the given set of rows.
     */
    public void setTreeLocks(final PremiumRow.EditableState state, PremiumRow... rows) {
        walkTree(RecursionType.Both, new Function1Void<PremiumRow>() {
            @Override
            public void apply(PremiumRow arg1) {
                arg1.setEditableState(state);
            }
        }, rows);

        List<PremiumRow> rowList = Arrays.asList(rows);
        if (rowList.isEmpty()) {
            fees.forEach(row -> row.setEditableState(state));
        }
    }

    /**
     * Write the contents of a given PremiumRow back into riskData.
     *
     * @param row The row to be written.
     */
    public void writeRowToObject(PremiumRow row) {
        ROMPayablesObject payables = row.getPayables();
        ROMPremium chargedPremium;
        ROMPremium technicalPremium;
        if (row.getPayables() instanceof ROMFee) {
            chargedPremium = payables.getFeePayables().getVariant().getChargedPremium();
            technicalPremium = payables.getFeePayables().getVariant().getTechnicalPremium();
        } else {
            chargedPremium = payables.getPayables().getVariant().getChargedPremium();
            technicalPremium = payables.getPayables().getVariant().getTechnicalPremium();
        }

        writeToObject(payables, chargedPremium, row, PremiumConstants.PremiumColumnCode.GrossChargedPremium.toString(),
                PremiumConstants.PremiumColumnCode.ChargedCommission.toString(), PremiumConstants.PremiumColumnCode.NetChargedPremium.toString(), true);
        writeToObject(payables, technicalPremium, row, PremiumConstants.PremiumColumnCode.GrossTechnicalPremium.toString(),
                PremiumConstants.PremiumColumnCode.TechnicalCommission.toString(), PremiumConstants.PremiumColumnCode.NetTechnicalPremium.toString(), false);
    }


    /**
     * A convenience method for writing values into the riskData. Converts the given strings to BigDecimal and writes them
     * to the ROMPremium.
     *
     * @param premium        The premium object to write to. Should be TechnicalPremium or ChargedPremium
     * @param premiumCode    String form of the Premium value. Read directly from the PremiumRow
     * @param commissionCode String form of the Commission value. Read directly from the PremiumRow
     */
    private void writeToObject(ROMPayablesObject payablesObject, ROMPremium premium, PremiumRow row, String premiumCode, String commissionCode, String netPremiumCode, boolean isCharged) {
        String grossPremium = row.getUserValues().get(premiumCode);
        String commission = row.getUserValues().get(commissionCode);
        String netPremium = row.getUserValues().get(netPremiumCode);

        String originalGross = row.getOriginalValues().get(premiumCode);
        String originalCommission = row.getOriginalValues().get(commissionCode);
        String originalNetPremium = row.getOriginalValues().get(netPremiumCode);



        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal comm = BigDecimal.ZERO;
        BigDecimal oGross = BigDecimal.ZERO;
        BigDecimal oComm = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal oNet = BigDecimal.ZERO;

        // Convert each premium value into it's numeric value
        if (StringUtils.isNotEmpty(grossPremium)) {
            gross = new BigDecimal(grossPremium);
        }

        if (StringUtils.isNotEmpty(commission)) {
            comm = new BigDecimal(commission);
        }

        if (StringUtils.isNotEmpty(originalGross)) {
            oGross = new BigDecimal(originalGross);
        }

        if (StringUtils.isNotEmpty(originalCommission)) {
            oComm = new BigDecimal(originalCommission);
        }

        if (StringUtils.isNotEmpty(netPremium)) {
            net = new BigDecimal(netPremium);
        }

        if (StringUtils.isNotEmpty(originalNetPremium)) {
            oNet = new BigDecimal(originalNetPremium);
        }
        if (isCharged) {
            String chargedPremiumRate = row.getUserValues().get(PremiumConstants.TABLE_RATE);
            String charedCommissionPercentage = row.getUserValues().get(PremiumConstants.PremiumColumnCode.CommissionPercentage.toString());
            String oChargedPremiumRate = row.getUserValues().get(PremiumConstants.TABLE_RATE + "oldCharged");
            BigDecimal sumInsured = (BigDecimal) payablesObject.get(PremiumValueKey.RATE_SUM_INSURED);
            BigDecimal previousPolicyPremiumChargeDaysAfterEffectDate = BigDecimal.ONE;

            payablesObject.put(PremiumValueKey.MAX_MIN_RANGE_APPLIED, false);
            //NZSME-1293 Generalized the solution for all Endorsement scenarios. if Premium updated over Rate update , we need to clear the Annual Premium Value.
            if (gross != null && oGross.compareTo(gross) != 0 && PolicyTransaction.Endorsement.equals(pom.getPolicyTransaction())) {
                payablesObject.put(PremiumValueKey.ENDORESMENT_PREMIUM_ADJUST, true);
                payablesObject.put(PremiumValueKey.ENDORESMENT_ANNUAL_PREMIUM, null);
            }
            if(comm.compareTo(oComm)!=0){
                payablesObject.put(PremiumConstants.ADJUSTED_COMM_PERCENTAGE, true);
            }

            if(charedCommissionPercentage !=null){
                BigDecimal commissionPercentVal = new BigDecimal(charedCommissionPercentage);
                if (commissionPercentVal.compareTo(new BigDecimal(100)) >= 0) {
                    return;
                }
                comm=calculateCommissionFromPercentage(charedCommissionPercentage,payablesObject);
                //BigDecimal baseCommRate = payablesObject.getPayables().getVariant().getChargedPremium().getCommissionPercentage();
                payablesObject.put(PremiumConstants.ADJUSTED_COMM_PERCENTAGE, true);
            }
            if (chargedPremiumRate != null) {

                BigDecimal chargedRate = BigDecimal.ZERO;

                BigDecimal oChargedRate = BigDecimal.ZERO;


                BigDecimal premiumDaysPolicyinYear = BigDecimal.valueOf(TAPASRiskData.getRiskData(pom).getPremiumDaysInPolicyYear());
                BigDecimal premiumChargeDaysAfterEffDate = getPolicyDays(pom);
                Amount previousGross = payablesObject.getPayables().getCycleToDate().getChargedPremium().getPremium();
                if ((SessionUtils.getPreviousPOM(SessionUtils.getCurrentSession()) != null)) {
                    previousPolicyPremiumChargeDaysAfterEffectDate = getPolicyDays(SessionUtils.getPreviousPOM(SessionUtils.getCurrentSession()));
                }
                BigDecimal pGross = BigDecimal.ZERO;


                if (StringUtils.isNotEmpty(chargedPremiumRate)) {
                    chargedRate = new BigDecimal(chargedPremiumRate);
                    payablesObject.put(PremiumValueKey.USERS_INPUT_RATE, chargedRate);
                }

                if (StringUtils.isNotEmpty(oChargedPremiumRate)) {
                    oChargedRate = new BigDecimal(oChargedPremiumRate);
                }

                if (previousGross != null && !previousGross.isNullOrZero()) {
                    pGross = new BigDecimal(String.valueOf(previousGross));
                    if (!previousPolicyPremiumChargeDaysAfterEffectDate.equals(premiumDaysPolicyinYear)) {
                        pGross = PremiumCalcUtils.divide(pGross, previousPolicyPremiumChargeDaysAfterEffectDate);
                        pGross = pGross.multiply(premiumDaysPolicyinYear);
                    }

                }

                if (chargedRate.compareTo(oChargedRate) != 0 && grossPremium != null && gross.compareTo(oGross) == 0 && sumInsured != null) {
                    if (PolicyTransaction.Endorsement.equals(pom.getPolicyTransaction())) {
                        gross = sumInsured.multiply(chargedRate);
                        payablesObject.put(PremiumValueKey.ENDORESMENT_ANNUAL_PREMIUM, gross);
                        if (isRPTransaction(payablesObject)) {
                            gross = PremiumCalcUtils.divide(gross, premiumDaysPolicyinYear);
                            gross = gross.multiply(premiumChargeDaysAfterEffDate);
                        }
                        gross = gross.subtract(pGross);

                    } else {
                        gross = sumInsured.multiply(chargedRate);
                    }

                }
                if (premiumChargeDaysAfterEffDate != null && premiumDaysPolicyinYear.compareTo(premiumChargeDaysAfterEffDate) != 0 && !PolicyTransaction.Import.equals(pom.getPolicyTransaction()) && !isRPTransaction(payablesObject)) {
                    gross = PremiumCalcUtils.divide(gross, premiumDaysPolicyinYear);
                    gross = gross.multiply(premiumChargeDaysAfterEffDate);
                }

            }


        }

        // Run rating/balance

        // TRANSRND-4123: Move setting of intended premiums and commissions to the backend, so that any desired logic can be handled there.
        // use compareTo instead of equals because 0 != 0.00 with BigDecimal
        Event event = null;
        if (payablesObject instanceof ROMFee) {
            // Fees only have charged premium editable, don't need that fork
            if (gross.compareTo(oGross) != 0) {
                event = new FeeEvent(FeeEvent.EventName.SetIntendedFee, pom, prod, payablesObject, premium, gross, PremiumAdjustmentType.GrossPremiumAdjustment);
                EventService eventService = TAPASAppContext.getService(prod, EventService.class);
                eventService.processEvent(event, EventAction.NullEventAction);
            }
        } else {
            if (gross.compareTo(oGross) != 0) {
                event = new PremiumEvent(PremiumEvent.EventName.SetIntendedPremium, pom, prod, payablesObject, premium, gross, comm, PremiumAdjustmentType.GrossPremiumAdjustment);
                EventService eventService = TAPASAppContext.getService(prod, EventService.class);
                eventService.processEvent(event, EventAction.NullEventAction);
            } else if (net.compareTo(oNet) != 0) {
                event = new PremiumEvent(PremiumEvent.EventName.SetIntendedPremium, pom, prod, payablesObject, premium, gross.subtract(oNet).subtract(oComm).add(net).add(comm), comm, PremiumAdjustmentType.GrossPremiumAdjustment);
                EventService eventService = TAPASAppContext.getService(prod, EventService.class);
                eventService.processEvent(event, EventAction.NullEventAction);
            } else if (comm.compareTo(oComm) != 0) {
                event = new PremiumEvent(PremiumEvent.EventName.SetIntendedPremium, pom, prod, payablesObject, premium, gross.subtract(oComm).add(comm), comm, PremiumAdjustmentType.CommissionAdjustment);
                EventService eventService = TAPASAppContext.getService(prod, EventService.class);
                eventService.processEvent(event, EventAction.NullEventAction);
            }// If neither has changed, don't update any values. (Stops overwrites in process adjustments)
        }
    }

    /**
     * Writes all PremiumRow's to riskData
     */
    public void writeAllRows() {
        for (PremiumRow row : listFlattenedRows()) {
            writeRowToObject(row);
        }

        for (PremiumRow row : getFees()) {
            writeRowToObject(row);
        }

    }

    /**
     * A Utility method that walks along the tree of PremiumRows and applies a function to any element it encounters.
     * Used by Refresh, Reset, Expand/Contract and Lock methods.
     *
     * @param recurse   How the tree will be walked. None means that only this element will be applied. Children means this
     *                  element and all it's children. Parent means this element and all of its parents. Both means this element,
     *                  all of its children and all of its parents.
     * @param operation The function to apply to each node
     * @param rows      the rows to start on. If empty, the list PremiumRowProcessor.rows will be used.
     */
    private void walkTree(RecursionType recurse, Function1Void<PremiumRow> operation, PremiumRow... rows) {
        List<PremiumRow> rowList = Arrays.asList(rows);
        if (rowList.isEmpty()) {
            rowList = this.rows;
        }

        for (PremiumRow row : rowList) {
            operation.apply(row);

            if (recurse == RecursionType.Children || recurse == RecursionType.Both) {
                List<PremiumRow> children = row.getChildren();
                if (!children.isEmpty()) {
                    walkTree(RecursionType.Children, operation, children.toArray(new PremiumRow[children.size()]));
                }
            }

            if (recurse == RecursionType.Parent || recurse == RecursionType.Both) {
                if (row.getParent() != null) {
                    walkTree(RecursionType.Parent, operation, row.getParent());
                }
            }
        }
    }

    /**
     * Returns a flattened list of all PremiumRow's in the hierarchy
     */
    public List<PremiumRow> listFlattenedRows() {
        List<PremiumRow> flat = new ArrayList<>();
        for (PremiumRow premiumRow : getRows()) {
            flat.add(premiumRow);
            flat.addAll(listFlattenedRows(premiumRow));
        }

        return flat;
    }

    /**
     * Returns a flattened list of all PremiumRow's in the hierarchy, starting at the given row.
     */
    private List<PremiumRow> listFlattenedRows(PremiumRow premiumRow) {
        List<PremiumRow> flat = new ArrayList<>();
        for (PremiumRow child : premiumRow.getChildren()) {
            flat.add(child);
            flat.addAll(listFlattenedRows(child));
        }

        return flat;
    }

    public List<PremiumRow> listRowsAndFees() {
        ArrayList<PremiumRow> topLevel = new ArrayList<>(rows);
        topLevel.addAll(fees);
        return topLevel;
    }

    /**
     * Returns list of Adjustable Rows
     */
    public List<PremiumRow> listAdjustableRows() {
        final List<PremiumRow> premiumRows = new ArrayList<>();
        final List<PremiumRow> processRows = new ArrayList<>(rows);
        processRows.addAll(fees);
        walkTree(RecursionType.Children, new Function1Void<PremiumRow>() {
            @Override
            public void apply(PremiumRow arg1) {
                if (arg1.isEditable()) {
                    premiumRows.add(arg1);
                }
            }
        }, processRows.toArray(new PremiumRow[processRows.size()]));
        return premiumRows;
    }

    public PremiumRow getRow(String path) {
        return rowDictionary.get(path);
    }

    // ----- Getters

    public List<PremiumRow> getRows() {
        return rows;
    }

    public void setRows(List<PremiumRow> rows) {
        this.rows = rows;
    }

    public PremiumRow getFee(String path) {
        return rowDictionary.get(path);
    }

    public List<PremiumRow> getFees() {
        return fees;
    }

    public void setFees(List<PremiumRow> fees) {
        this.fees = fees;
    }

    // Create Dummy Row for Showing Total of children rows
    private PremiumRow createTotalsRow(PremiumRow row) {
        PremiumRow dummyRow = new PremiumRowProxy(row);
        dummyRow.setTotalRow(true);
        dummyRow.setVisible(hideTotalDummyRow);
        rowDictionary.put(dummyRow.getKey(), dummyRow);
        return dummyRow;
    }

    public boolean isAnyRowEditable() {
        return anyRowEditable;
    }

    private void resetPayables(PremiumRow row) {
        if (!row.isFee()) {
            row.getPayables().getPayables().setVariant(row.getOriginalROMPayables().getVariant().deepCopy());
            row.getPayables().getPayables().setFap(row.getOriginalROMPayables().getFap().deepCopy());
        } else {
            row.getPayables().getFeePayables().setVariant(row.getOriginalROMPayables().getVariant().deepCopy());
            row.getPayables().getFeePayables().setFap(row.getOriginalROMPayables().getFap().deepCopy());
        }
        refresh(row);
    }

    public void updateAllOriginalPayables() {
        List<PremiumRow> topLevel = listRowsAndFees();
        updateOriginalPayablesTree(RecursionType.Children, topLevel.toArray(new PremiumRow[topLevel.size()]));
    }

    private void updateOriginalPayablesTree(RecursionType recurse, PremiumRow... start) {
        walkTree(recurse, this::updateOriginalPayables, start);
    }

    private void updateOriginalPayables(PremiumRow row) {
        if (!row.isFee()) {
            row.setOriginalROMPayables(row.getPayables().getPayables().deepCopy());
        } else {
            row.setOriginalROMPayables(row.getPayables().getFeePayables().deepCopy());
        }
    }

    private void createPolicyLevelStampDutyRow(PremiumRow row) {
        try {
            DEConfig deConfig = ProductUtils.getSessionProduct().getDataDictionary().get(PremiumConstants.PREMIUM_DETAILS).findConfig(PremiumConstants.MODULE_NAME, PremiumConstants.CREATE_POLICY_LEVEL_CHARGE_ROW);

            if (deConfig == null || deConfig.getValue() == null) {
                return;
            }
            boolean createRows = Boolean.parseBoolean(deConfig.getValue());
            Product product = ProductUtils.getSessionProduct();
            List<DECharge> chargeList = product.getDataDictionary().get(UIConstants.POLICY_PAGE_DE).getCharges();

            if (createRows) {
                List<Charge> chargesList = TAPASRiskData.getRiskData(pom).getTotalPremiumPayables().getVariant().getChargedPremium().getCharges();

                if (chargeList != null && !chargesList.isEmpty()) {
                    for (Charge charges : chargesList) {
                        if (!(charges.getChargeType().equals(ProductConstants.SD) || charges.getChargeType().equals(ProductConstants.PC)
                                || charges.getChargeType().equals(ProductConstants.IAL) || charges.getChargeType().equals(ProductConstants.ECIL))) {
                            continue;
                        }
                        if (row.getHeading() != null && (row.getHeading().equalsIgnoreCase(UIConstants.POLICY_DETAILS) || row.getHeading().equalsIgnoreCase("Policy"))) {
                            PremiumRow chargeRow = new PremiumRow(new ROMPayablesObject());
                            if (charges.getChargeType().equals(ProductConstants.SD) || charges.getChargeType().equals(ProductConstants.PC)
                                    || charges.getChargeType().equals(ProductConstants.IAL) || charges.getChargeType().equals(ProductConstants.ECIL)) {
                                if (pom.getPolicyTransaction() == PolicyTransaction.Cancellation) {
                                    chargeRow.setDeleted(true);
                                } else {
                                    chargeRow.setDeleted(false);
                                }
                                if (charges.getChargeType().equals(ProductConstants.SD)) {
                                    chargeRow.setHeading("Stamp Duty");
                                    if(pom.getNamedInfo(NamedInfo.Edited_Stamp_Duty) != null && !StringUtils.isEmpty(pom.getNamedInfo(NamedInfo.Edited_Stamp_Duty).getInfo())) {
                                        charges.setAmount(new Amount(pom.getNamedInfo(NamedInfo.Edited_Stamp_Duty).getInfo().toString()));
                                    }
                                    chargeRow.setStampDutyRow(true);
                                } else if (charges.getChargeType().equals(ProductConstants.PC)){
                                    chargeRow.setHeading("Policy Cost");
                                    if(pom.getNamedInfo(NamedInfo.Edited_Policy_Cost) != null && !StringUtils.isEmpty(pom.getNamedInfo(NamedInfo.Edited_Policy_Cost).getInfo())) {
                                        charges.setAmount(new Amount(pom.getNamedInfo(NamedInfo.Edited_Policy_Cost).getInfo().toString()));
                                    }
                                    chargeRow.setPolicyCostRow(true);
                                } else if (charges.getChargeType().equals(ProductConstants.IAL)){
                                    chargeRow.setHeading("IA Levy");
                                    if(pom.getNamedInfo(NamedInfo.Edited_IA_Levy) != null && !StringUtils.isEmpty(pom.getNamedInfo(NamedInfo.Edited_IA_Levy).getInfo())) {
                                        charges.setAmount(new Amount(pom.getNamedInfo(NamedInfo.Edited_IA_Levy).getInfo().toString()));
                                        if(new BigDecimal(pom.getNamedInfo(NamedInfo.Edited_IA_Levy).getInfo()).compareTo(BigDecimal.ZERO)==0){
                                            pom.setNamedInfo(NamedInfo.Edited_IA_Levy,"");
                                        }
                                    }
                                    chargeRow.setIaLevyRow(true);
                                } else if (charges.getChargeType().equals(ProductConstants.ECIL)){
                                    chargeRow.setHeading("ECI Levy");
                                    if(pom.getNamedInfo(NamedInfo.Edited_ECI_Levy) != null && !StringUtils.isEmpty(pom.getNamedInfo(NamedInfo.Edited_ECI_Levy).getInfo())) {
                                        charges.setAmount(new Amount(pom.getNamedInfo(NamedInfo.Edited_ECI_Levy).getInfo().toString()));
                                        if(new BigDecimal(pom.getNamedInfo(NamedInfo.Edited_ECI_Levy).getInfo()).compareTo(BigDecimal.ZERO)==0){
                                            pom.setNamedInfo(NamedInfo.Edited_ECI_Levy,"");
                                        }
                                    }
                                    chargeRow.setEcilLevyRow(true);
                                }
                                chargeRow.setState(PremiumRow.State.ReadOnly);
                                chargeRow.setSourceType(UIConstants.SOURCE_STANDARD);
                                chargeRow.setKey(null);  //riskData.root.insuredObjects[0]
                                chargeRow.setCalcType(UIConstants.PAGE_RATED);  //rated
                                chargeRow.setColumns(columns);
                                chargeRow.getUserValues().put(PremiumConstants.TABLE_CHARGES, charges.getAmount().toString());
                                chargeRow.getOriginalValues().put(PremiumConstants.TABLE_CHARGES, charges.getAmount().toString());
                            } else {
                                chargeRow.getUserValues().put(PremiumConstants.TABLE_CHARGES, "0");
                                chargeRow.getOriginalValues().put(PremiumConstants.TABLE_CHARGES, "0");
                            }
                            rows.add(chargeRow);
                        }
                    }
                    TAPASRiskData.getRiskData(pom).getTotalPremiumPayables().getVariant().getChargedPremium().setCharges(chargesList);
                }
            }
        } catch (Exception e) {
            LOG.error("Exception Occured in creating policy level charge row", e);
        }
    }

    /**
     * Fetch the commission rate. If Rated payables are available, then it must be fetched from there, else evaluated as
     * a percentage of commission amount over Gross Premium, or defaults to 0 if commission percentage isn't available
     *
     * @param premium       Technical or Charged ROM Premium object on which the commission percentage is being queried
     * @param ratedPayables rated payables on the structure node if available
     * @param isTechnical   if technical or charged premium
     */
    private String getCommissionRate(ROMPremium premium, ROMRatedPayables ratedPayables, boolean isTechnical) {
        BigDecimal commissionRate = null;
        if (ratedPayables != null) {
            if (isTechnical && ratedPayables.getCstd() != null) {
                commissionRate = ratedPayables.getCstd().multiply(new BigDecimal(100));
            } else if (ratedPayables.getCbase() != null) {
                commissionRate = ratedPayables.getCbase().multiply(new BigDecimal(100));
            }
        }

        if (PremiumUtils.isCommissionPercentEditable() && PremiumUtils.isNonFinancialEndorsement(pom)) {
            commissionRate = new BigDecimal(0);
        }
        if (commissionRate == null) {
            commissionRate = premium.getCommissionPercentage() != null ? premium.getCommissionPercentage() : BigDecimal.ZERO;
        }
        return commissionRate.setScale(2, BigDecimal.ROUND_HALF_EVEN).toString();
    }

    public enum RecursionType {
        None, Children, Parent, Both
    }

    // Below code is added under NZSME-1183 is to calculate the policy days in order to calcuate the premium.
    private BigDecimal getPolicyDays(Policy policy) {

        LeapYearStrategyFactory leapYearStrategyHelper = new LeapYearStrategyFactory();
        Product product = ProductUtils.getSessionProduct();
        LeapYearStrategy leapYearStrategy = leapYearStrategyHelper.getLeapYearStrategy(policy, product);
        BigDecimal days = BigDecimal.valueOf(DateUtils.daysBetweenDates(policy.getEffectiveDate(), policy.getEndDate()));
        if (leapYearStrategy.dateRangeHavingLeapYearDate(policy.getEffectiveDate(), policy.getEndDate()) && LeapYearStrategy.NO_ADDITIONAL_DAY_CHARGE_FIXED_DAILY_RATE.equals(leapYearStrategy.getLeapYearStrategyType())) {
            days = days.subtract(BigDecimal.valueOf(1));
        }
        return days;
    }

    private boolean isRPTransaction(ROMPayablesObject payables) {

        Amount variantPremium = payables.getPayables().getVariant().getChargedPremium().getPremium();
        if (variantPremium != null && variantPremium.isNegative()) {
            return true;
        }
        return false;
    }

    private BigDecimal calculateCommissionFromPercentage(String commissionPercent, ROMPayablesObject payablesObject) {
        BigDecimal commissionPercentValue = BigDecimal.ZERO;
        BigDecimal commissionRato = BigDecimal.ONE;
        BigDecimal netPremium = payablesObject.getPayables().getVariant().getChargedPremium().getNetPremium().getValue();
        if (StringUtils.isNotEmpty(commissionPercent)) {
            commissionPercentValue = new BigDecimal(commissionPercent);
            commissionPercentValue = commissionPercentValue.divide(new BigDecimal(100));
        }
        commissionRato = PremiumCalcUtils.divide(commissionPercentValue, (BigDecimal.ONE.subtract(commissionPercentValue)));
        BigDecimal commission = netPremium.multiply(commissionRato);

        return commission;
    }
}