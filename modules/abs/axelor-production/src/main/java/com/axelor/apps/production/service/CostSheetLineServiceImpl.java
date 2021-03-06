/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.production.service;

import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.UnitRepository;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.production.db.CostSheetGroup;
import com.axelor.apps.production.db.CostSheetLine;
import com.axelor.apps.production.db.WorkCenter;
import com.axelor.apps.production.db.repo.CostSheetGroupRepository;
import com.axelor.apps.production.db.repo.CostSheetLineRepository;
import com.axelor.apps.production.service.app.AppProductionService;
import com.axelor.exception.AxelorException;
import com.beust.jcommander.internal.Lists;
import com.google.inject.Inject;

public class CostSheetLineServiceImpl implements CostSheetLineService  {

	private final Logger logger = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );
	
	protected AppProductionService appProductionService;
	protected CostSheetGroupRepository costSheetGroupRepository;
	protected UnitConversionService unitConversionService;
	protected UnitRepository unitRepo;
	
	@Inject
	public CostSheetLineServiceImpl(AppProductionService appProductionService, CostSheetGroupRepository costSheetGroupRepository,
			UnitConversionService unitConversionService, UnitRepository unitRepo)  {
		this.appProductionService = appProductionService;
		this.costSheetGroupRepository = costSheetGroupRepository;
		this.unitConversionService = unitConversionService;
		this.unitRepo = unitRepo;
		
	}

	public CostSheetLine createCostSheetLine(String name, String code, int bomLevel, BigDecimal consumptionQty, BigDecimal costPrice, 
			CostSheetGroup costSheetGroup, Product product, int typeSelect, Unit unit, WorkCenter workCenter, CostSheetLine parentCostSheetLine)  {
		
		logger.debug("Add a new line of cost sheet ({} - {} - BOM level {} - cost price : {})", code, name, bomLevel, costPrice);
		
		CostSheetLine costSheetLine = new CostSheetLine(code, name);
		costSheetLine.setBomLevel(bomLevel);
		costSheetLine.setConsumptionQty(consumptionQty);
		costSheetLine.setCostSheetGroup(costSheetGroup);
		costSheetLine.setProduct(product);
		costSheetLine.setTypeSelect(typeSelect);
		if(unit != null){
			costSheetLine.setUnit(unitRepo.find(unit.getId()));
		}
		costSheetLine.setWorkCenter(workCenter);
		
		if(costPrice == null)  {
			costPrice = BigDecimal.ZERO;
		}

		costSheetLine.setCostPrice(costPrice.setScale(appProductionService.getNbDecimalDigitForUnitPrice(), BigDecimal.ROUND_HALF_EVEN));
		
		if(parentCostSheetLine != null)  {
			parentCostSheetLine.addCostSheetLineListItem(costSheetLine);
			this.createIndirectCostSheetGroups(costSheetGroup, parentCostSheetLine, costSheetLine.getCostPrice());
		}
		
		return costSheetLine;
	}

	public CostSheetLine createProducedProductCostSheetLine(Product product, Unit unit, BigDecimal consumptionQty)  {
		
		return this.createCostSheetLine(product.getName(), product.getCode(), 0, consumptionQty, null, product.getCostSheetGroup(), 
				product, CostSheetLineRepository.TYPE_PRODUCED_PRODUCT, unit, null, null);
	}
	
	public CostSheetLine createResidualProductCostSheetLine(Product product, Unit unit, BigDecimal consumptionQty) throws AxelorException  {
		
		if(appProductionService.getAppProduction().getSubtractProdResidualOnCostSheet())  {  consumptionQty = consumptionQty.negate();  }
		
		BigDecimal costPrice = unitConversionService.convert(product.getUnit(), unit, product.getCostPrice().multiply(consumptionQty));
		
		return this.createCostSheetLine(product.getName(), product.getCode(), 0, consumptionQty, costPrice, product.getCostSheetGroup(), 
				product, CostSheetLineRepository.TYPE_PRODUCED_PRODUCT, unit, null, null);
	}
 	
	public CostSheetLine createConsumedProductCostSheetLine(Product product, Unit unit, int bomLevel, CostSheetLine parentCostSheetLine, BigDecimal consumptionQty) throws AxelorException  {
		
		BigDecimal costPrice = unitConversionService.convert(product.getUnit(), unit, product.getCostPrice().multiply(consumptionQty));
		
		return this.createCostSheetLine(product.getName(), product.getCode(), bomLevel, consumptionQty, costPrice, product.getCostSheetGroup(), 
				product, CostSheetLineRepository.TYPE_CONSUMED_PRODUCT, unit, null, parentCostSheetLine);
	
	}
	
	public CostSheetLine createConsumedProductWasteCostSheetLine(Product product, Unit unit, int bomLevel, CostSheetLine parentCostSheetLine, BigDecimal consumptionQty, BigDecimal wasteRate) throws AxelorException  {
		
		BigDecimal qty = consumptionQty.multiply(wasteRate).divide(new BigDecimal("100"), appProductionService.getNbDecimalDigitForBomQty(), BigDecimal.ROUND_HALF_EVEN);
		
		BigDecimal costPrice = unitConversionService.convert(product.getUnit(), unit, product.getCostPrice().multiply(qty));
		
		return this.createCostSheetLine(product.getName(), product.getCode(), bomLevel, 
				qty.setScale(appProductionService.getNbDecimalDigitForBomQty(), RoundingMode.HALF_EVEN), 
				costPrice.setScale(appProductionService.getNbDecimalDigitForUnitPrice(), RoundingMode.HALF_EVEN), 
				product.getCostSheetGroup(), product, CostSheetLineRepository.TYPE_CONSUMED_PRODUCT_WASTE, unit, null, parentCostSheetLine);
	
	}
	
	public CostSheetLine createWorkCenterCostSheetLine(WorkCenter workCenter, int priority, int bomLevel, CostSheetLine parentCostSheetLine, BigDecimal consumptionQty, BigDecimal costPrice, Unit unit)  {
		
		return this.createCostSheetLine(workCenter.getName(), priority + " - " + workCenter.getCode(), bomLevel, consumptionQty, costPrice, workCenter.getCostSheetGroup(), 
				null, CostSheetLineRepository.TYPE_WORK_CENTER, unit, workCenter, parentCostSheetLine);
		
	}
 	
	protected List<CostSheetGroup> getIndirectCostSheetGroups(CostSheetGroup costSheetGroup)  {
		
		if(costSheetGroup == null)  {  return Lists.newArrayList();  }
		
		return costSheetGroupRepository.all().filter("?1 member of self.costSheetGroupSet AND self.costTypeSelect = ?2", costSheetGroup, CostSheetGroupRepository.COST_TYPE_INDIRECT).fetch();
		
	}
	
	protected void createIndirectCostSheetGroups(CostSheetGroup costSheetGroup, CostSheetLine parentCostSheetLine, BigDecimal costPrice)  {
		
		if(costSheetGroup == null)  {  return;  }
		
		for(CostSheetGroup indirectCostSheetGroup : this.getIndirectCostSheetGroups(costSheetGroup))  {
			
			this.createIndirectCostSheetLine(parentCostSheetLine, indirectCostSheetGroup, costPrice);
		}
	}
	
	protected CostSheetLine createIndirectCostSheetLine(CostSheetLine parentCostSheetLine, CostSheetGroup costSheetGroup, BigDecimal costPrice)  {
		
		CostSheetLine indirectCostSheetLine = this.getCostSheetLine(costSheetGroup, parentCostSheetLine);
		
		if(indirectCostSheetLine == null)  {  
				indirectCostSheetLine = this.createCostSheetLine(costSheetGroup.getCode(), costSheetGroup.getName(), parentCostSheetLine.getBomLevel()+1, BigDecimal.ONE, null, 
						costSheetGroup, null, CostSheetLineRepository.TYPE_INDIRECT_COST, null, null, parentCostSheetLine);
				parentCostSheetLine.addCostSheetLineListItem(indirectCostSheetLine);		
		}
		
		indirectCostSheetLine.setCostPrice(indirectCostSheetLine.getCostPrice().add(this.getIndirectCostPrice(costSheetGroup, costPrice)));
		
		return indirectCostSheetLine;
		
	}
	
	protected BigDecimal getIndirectCostPrice(CostSheetGroup costSheetGroup, BigDecimal costPrice)  {

		BigDecimal indirectCostPrice = BigDecimal.ZERO;
		
		indirectCostPrice = costPrice.multiply(costSheetGroup.getRate()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN);
		
		if(costSheetGroup.getRateTypeSelect() == CostSheetGroupRepository.COST_TYPE_SURCHARGE) {
			indirectCostPrice = indirectCostPrice.add(costPrice);
		}
		
		return indirectCostPrice;
		
	}
	
	protected CostSheetLine getCostSheetLine(CostSheetGroup indirectCostSheetGroup, CostSheetLine parentCostSheetLine)  {
		
		for(CostSheetLine costSheetLine : parentCostSheetLine.getCostSheetLineList())  {
			
			CostSheetGroup costSheetGroup = costSheetLine.getCostSheetGroup();
			
			if(costSheetGroup != null && costSheetGroup.getCostTypeSelect() == CostSheetGroupRepository.COST_TYPE_INDIRECT && costSheetGroup.equals(indirectCostSheetGroup))  {
				return costSheetLine;
			}
			
		}
		
		return null;
		
	}
	
	
	
	
}
